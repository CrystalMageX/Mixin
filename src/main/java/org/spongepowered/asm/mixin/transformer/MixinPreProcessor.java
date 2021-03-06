/*
 * This file is part of Mixin, licensed under the MIT License (MIT).
 *
 * Copyright (c) SpongePowered <https://www.spongepowered.org>
 * Copyright (c) contributors
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package org.spongepowered.asm.mixin.transformer;

import java.lang.annotation.Annotation;
import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.spongepowered.asm.lib.Opcodes;
import org.spongepowered.asm.lib.tree.AbstractInsnNode;
import org.spongepowered.asm.lib.tree.AnnotationNode;
import org.spongepowered.asm.lib.tree.ClassNode;
import org.spongepowered.asm.lib.tree.FieldInsnNode;
import org.spongepowered.asm.lib.tree.FieldNode;
import org.spongepowered.asm.lib.tree.MethodInsnNode;
import org.spongepowered.asm.lib.tree.MethodNode;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.MixinEnvironment;
import org.spongepowered.asm.mixin.MixinEnvironment.Option;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.RemapperChain;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.Surrogate;
import org.spongepowered.asm.mixin.injection.struct.InjectionInfo;
import org.spongepowered.asm.mixin.transformer.ClassInfo.Field;
import org.spongepowered.asm.mixin.transformer.ClassInfo.Method;
import org.spongepowered.asm.mixin.transformer.meta.MixinRenamed;
import org.spongepowered.asm.util.ASMHelper;
import org.spongepowered.asm.util.Constants;

/**
 * <p>Mixin bytecode pre-processor. This class is responsible for bytecode pre-
 * processing tasks required to be performed on mixin bytecode before the mixin
 * can be applied. In previous versions the duties performed by this class were
 * performed by {@link MixinInfo}.</p>
 * 
 * <p>Before a mixin can be applied to the target class, it is necessary to
 * convert certain aspects of the mixin bytecode into the intended final form of
 * the mixin, this involves for example stripping the prefix from shadow and
 * soft-implemented methods. This preparation is done in two stages: first the
 * target-context-insensitive transformations are applied (this also acts as a
 * validation pass when the mixin is first loaded) and then transformations
 * which depend on the target class are applied in a second stage.</p>
 * 
 * <p>The validation pass propagates method renames into the metadata tree and
 * thus changes made during this phase are visible to all other mixins. The
 * target-context-sensitive pass on the other hand can only operate on private
 * class members for obvious reasons.</p>  
 */
class MixinPreProcessor {
    
    /**
     * Logger
     */
    private static final Logger logger = LogManager.getLogger("mixin");

    /**
     * The mixin
     */
    protected final MixinInfo mixin;
    
    /**
     * Mixin class node
     */
    protected final ClassNode classNode;
    
    private final boolean verboseLogging;
    
    private boolean prepared, attached;

    MixinPreProcessor(MixinInfo mixin, ClassNode classNode) {
        this.mixin = mixin;
        this.classNode = classNode;
        this.verboseLogging = mixin.getParent().getEnvironment().getOption(Option.DEBUG_VERBOSE);
    }

    /**
     * Run the first pass. Propagates changes into the metadata tree.
     * 
     * @return Prepared classnode
     */
    MixinPreProcessor prepare() {
        if (!this.prepared) {
            this.prepared = true;
            
            for (MethodNode mixinMethod : this.classNode.methods) {
                Method method = this.mixin.getClassInfo().findMethod(mixinMethod);
                this.prepareMethod(mixinMethod, method);
            }
            
            for (FieldNode mixinField : this.classNode.fields) {
                this.prepareField(mixinField);
            }
        }
        
        return this;
    }

    protected void prepareMethod(MethodNode mixinMethod, Method method) {
        this.prepareShadow(mixinMethod, method);
        this.prepareSoftImplements(mixinMethod, method);
    }

    protected void prepareShadow(MethodNode mixinMethod, Method method) {
        AnnotationNode shadowAnnotation = ASMHelper.getVisibleAnnotation(mixinMethod, Shadow.class);
        if (shadowAnnotation == null) {
            return;
        }
        
        String prefix = ASMHelper.<String>getAnnotationValue(shadowAnnotation, "prefix", Shadow.class);
        if (mixinMethod.name.startsWith(prefix)) {
            ASMHelper.setVisibleAnnotation(mixinMethod, MixinRenamed.class, "originalName", mixinMethod.name);
            String newName = mixinMethod.name.substring(prefix.length());
            method.renameTo(newName);
            mixinMethod.name = newName;
        }
    }

    protected void prepareSoftImplements(MethodNode mixinMethod, Method method) {
        for (InterfaceInfo iface : this.mixin.getSoftImplements()) {
            if (iface.renameMethod(mixinMethod)) {
                method.renameTo(mixinMethod.name);
            }
        }
    }

    protected void prepareField(FieldNode mixinField) {
        // stub
    }

    MixinTargetContext createContextFor(TargetClassContext target) {
        MixinTargetContext context = new MixinTargetContext(this.mixin, this.classNode, target);
        this.attach(context);
        return context;
    }

    /**
     * Run the second pass, attach to the specified context
     * 
     * @param context
     */
    void attach(MixinTargetContext context) {
        if (this.attached) {
            throw new IllegalStateException("Preprocessor was already attached");
        }
        
        this.attached = true;
        
        // Perform context-sensitive attachment phase
        this.attachMethods(context);
        this.attachFields(context);
        
        // Apply transformations to the mixin bytecode
        this.transform(context);
    }

    protected void attachMethods(MixinTargetContext context) {
        for (Iterator<MethodNode> iter = this.classNode.methods.iterator(); iter.hasNext();) {
            MethodNode mixinMethod = iter.next();
            
            if (!this.validateMethod(context, mixinMethod)) {
                iter.remove();
                continue;
            }
            
            if (this.processInjectorMethod(context, mixinMethod)) {
                continue;
            }
            
            if (this.processMemberMethod(context, mixinMethod, Shadow.class, true, true)) {
                iter.remove();
                context.addShadowMethod(mixinMethod);
                continue;
            }

            if (this.processMemberMethod(context, mixinMethod, Overwrite.class, false, false)) {
                continue;
            }
            
            this.processMethod(mixinMethod);
        }
    }

    protected boolean validateMethod(MixinTargetContext context, MethodNode mixinMethod) {
        return true;
    }

    protected boolean processInjectorMethod(MixinTargetContext context, MethodNode mixinMethod) {
        AnnotationNode annotation = InjectionInfo.getInjectorAnnotation(context, mixinMethod);
        boolean surrogate = ASMHelper.getVisibleAnnotation(mixinMethod, Surrogate.class) != null;
        if (annotation == null && !surrogate) {
            return false;
        }
        
        String handlerName = context.getHandlerName(annotation, mixinMethod, surrogate);
        Method method = this.mixin.getClassInfo().findMethod(mixinMethod, ClassInfo.INCLUDE_ALL);
        method.renameTo(handlerName);
        mixinMethod.name = handlerName;
        return true;
    }
    
    protected boolean processMemberMethod(MixinTargetContext context, MethodNode mixinMethod, Class<? extends Annotation> annotationType,
            boolean mustExist, boolean mustBePrivate) {
        AnnotationNode annotation = ASMHelper.getVisibleAnnotation(mixinMethod, annotationType);
        if (annotation == null) {
            return false;
        }
        
        Method method = this.mixin.getClassInfo().findMethod(mixinMethod, ClassInfo.INCLUDE_ALL);
        MethodNode target = MixinPreProcessor.findMethod(context.getTargetClass(), mixinMethod, annotation);
        
        if (target == null) {
            if (!mustExist) {
                return false;
            }
            target = MixinPreProcessor.findRemappedMethod(context.getTargetClass(), mixinMethod);
            if (target == null) {
                throw new InvalidMixinException(this.mixin, annotationType.getSimpleName() + " method " + mixinMethod.name
                        + " was not located in the target class");
            }
            mixinMethod.name = target.name;
            method.renameTo(target.name);
        }
        
        if (Constants.CTOR.equals(target.name)) {
            throw new InvalidMixinException(this.mixin, "Nice try! Cannot alias a constructor!");
        }
        
        if (!target.name.equals(mixinMethod.name)) {
            if (mustBePrivate && (target.access & Opcodes.ACC_PRIVATE) == 0) {
                throw new InvalidMixinException(this.mixin, "Non-private method cannot be aliased. Found " + target.name);
            }
            
            mixinMethod.name = target.name;
            method.renameTo(target.name);
        }
        
        return true;
    }

    protected void processMethod(MethodNode mixinMethod) {
        Method method = this.mixin.getClassInfo().findMethod(mixinMethod);
        if (method == null) {
            return;
        }
        
        Method parentMethod = this.mixin.getClassInfo().findMethodInHierarchy(mixinMethod, false);
        if (parentMethod != null && parentMethod.isRenamed()) {
            mixinMethod.name = parentMethod.getName();
            method.renameTo(parentMethod.getName());
        }
    }

    protected void attachFields(MixinTargetContext context) {
        for (Iterator<FieldNode> iter = this.classNode.fields.iterator(); iter.hasNext();) {
            FieldNode mixinField = iter.next();
            AnnotationNode shadow = ASMHelper.getVisibleAnnotation(mixinField, Shadow.class);
            boolean isFinal = ASMHelper.getVisibleAnnotation(mixinField, Final.class) != null;
            boolean isMutable = ASMHelper.getVisibleAnnotation(mixinField, Mutable.class) != null;
            if (!this.validateField(context, mixinField, shadow)) {
                iter.remove();
                continue;
            }
            
            context.transformDescriptor(mixinField);
            
            Field field = this.mixin.getClassInfo().findField(mixinField);
            FieldNode target = MixinPreProcessor.findField(context.getTargetClass(), mixinField, shadow);
            if (target == null) {
                if (shadow == null) {
                    continue;
                }
                target = MixinPreProcessor.findRemappedField(context.getTargetClass(), mixinField);
                if (target == null) {
                    // If this field is a shadow field but is NOT found in the target class, that's bad, mmkay
                    throw new InvalidMixinException(this.mixin, "Shadow field " + mixinField.name + " was not located in the target class");
                }
                mixinField.name = target.name;
                field.renameTo(target.name);
            } 
            
            // Check that the shadow field has a matching descriptor
            if (!target.desc.equals(mixinField.desc)) {
                throw new InvalidMixinException(this.mixin, "The field " + mixinField.name + " in the target class has a conflicting signature");
            }
            
            if (!target.name.equals(mixinField.name)) {
                if ((target.access & Opcodes.ACC_PRIVATE) == 0 && (target.access & Opcodes.ACC_SYNTHETIC) == 0) {
                    throw new InvalidMixinException(this.mixin, "Non-private field cannot be aliased. Found " + target.name);
                }
                
                mixinField.name = target.name;
                field.renameTo(target.name);
            }
            
            // Shadow fields get stripped from the mixin class
            iter.remove();
            
            if (shadow != null) {
                if (field == null) {
                    throw new InvalidMixinException(this.mixin, "Unable to locate field surrogate: " + mixinField.name + " in " + this.mixin);
                }
                field.setDecoratedFinal(isFinal, isMutable);

                if (this.verboseLogging && MixinApplicator.hasFlag(target, Opcodes.ACC_FINAL) != isFinal) {
                    String message = isFinal
                        ? "@Shadow field {}::{} is decorated with @Final but target is not final"
                        : "@Shadow target {}::{} is final but shadow is not decorated with @Final";
                    MixinPreProcessor.logger.warn(message, this.mixin, mixinField.name);
                }

                context.addShadowField(mixinField, field);
            }
        }
    }

    protected boolean validateField(MixinTargetContext context, FieldNode field, AnnotationNode shadow) {
        // Public static fields will fall foul of early static binding in java, including them in a mixin is an error condition
        if (MixinApplicator.hasFlag(field, Opcodes.ACC_STATIC)
                && !MixinApplicator.hasFlag(field, Opcodes.ACC_PRIVATE)
                && !MixinApplicator.hasFlag(field, Opcodes.ACC_SYNTHETIC)) {
            throw new InvalidMixinException(context, String.format("Mixin classes cannot contain visible static methods or fields, found %s",
                    field.name));
        }

        // Shadow fields can't have prefixes, it's meaningless for them anyway
        String prefix = ASMHelper.<String>getAnnotationValue(shadow, "prefix", Shadow.class);
        if (field.name.startsWith(prefix)) {
            throw new InvalidMixinException(context, String.format("Shadow field %s in %s has a shadow prefix. This is not allowed.",
                    field.name, context));
        }
        
        // Imaginary super fields get stripped from the class, but first we validate them
        if (Constants.IMAGINARY_SUPER.equals(field.name)) {
            if (field.access != Opcodes.ACC_PRIVATE) {
                throw new InvalidMixinException(this.mixin, "Imaginary super field " + field.name + " must be private and non-final");
            }
            if (!field.desc.equals("L" + this.mixin.getClassRef() + ";")) {
                throw new InvalidMixinException(this.mixin, "Imaginary super field " + field.name + " must have the same type as the parent mixin");
            }
            return false;
        }
        
        return true;
    }

    /**
     * Apply discovered method and field renames to method invocations and field
     * accesses in the mixin
     */
    protected void transform(MixinTargetContext context) {
        for (MethodNode mixinMethod : this.classNode.methods) {
            for (Iterator<AbstractInsnNode> iter = mixinMethod.instructions.iterator(); iter.hasNext();) {
                AbstractInsnNode insn = iter.next();
                if (insn instanceof MethodInsnNode) {
                    MethodInsnNode methodNode = (MethodInsnNode)insn;
                    Method method = ClassInfo.forName(methodNode.owner).findMethodInHierarchy(methodNode, true, ClassInfo.INCLUDE_PRIVATE);
                    if (method != null && method.isRenamed()) {
                        methodNode.name = method.getName();
                    }
                } else if (insn instanceof FieldInsnNode) {
                    FieldInsnNode fieldNode = (FieldInsnNode)insn;
                    Field field = ClassInfo.forName(fieldNode.owner).findField(fieldNode, ClassInfo.INCLUDE_PRIVATE);
                    if (field != null && field.isRenamed()) {
                        fieldNode.name = field.getName();
                    }
                }
            }
        }
    }

    protected static MethodNode findMethod(ClassNode classNode, MethodNode method, AnnotationNode annotation) {
        Deque<String> aliases = new LinkedList<String>();
        aliases.add(method.name);
        if (annotation != null) {
            List<String> aka = ASMHelper.<List<String>>getAnnotationValue(annotation, "aliases");
            if (aka != null) {
                aliases.addAll(aka);
            }
        }
        
        return MixinPreProcessor.findMethodRecursive(classNode, aliases, method.desc);
    }

    protected static MethodNode findRemappedMethod(ClassNode classNode, MethodNode method) {
        RemapperChain remapperChain = MixinEnvironment.getCurrentEnvironment().getRemappers();
        String remappedName = remapperChain.mapMethodName(classNode.name, method.name, method.desc);
        if (remappedName.equals(method.name)) {
            return null;
        }

        Deque<String> aliases = new LinkedList<String>();
        aliases.add(remappedName);
        
        return MixinPreProcessor.findMethodRecursive(classNode, aliases, method.desc);
    }
    
    private static MethodNode findMethodRecursive(ClassNode classNode, Deque<String> aliases, String desc) {
        String alias = aliases.poll();
        if (alias == null) {
            return null;
        }
        
        for (MethodNode target : classNode.methods) {
            if (target.name.equals(alias) && target.desc.equals(desc)) {
                return target;
            }
        }

        return MixinPreProcessor.findMethodRecursive(classNode, aliases, desc);
    }

    protected static FieldNode findField(ClassNode classNode, FieldNode field, AnnotationNode shadow) {
        Deque<String> aliases = new LinkedList<String>();
        aliases.add(field.name);
        if (shadow != null) {
            List<String> aka = ASMHelper.<List<String>>getAnnotationValue(shadow, "aliases");
            if (aka != null) {
                aliases.addAll(aka);
            }
        }
        
        return MixinPreProcessor.findFieldRecursive(classNode, aliases, field.desc);
    }

    protected static FieldNode findRemappedField(ClassNode classNode, FieldNode field) {
        RemapperChain remapperChain = MixinEnvironment.getCurrentEnvironment().getRemappers();
        String remappedName = remapperChain.mapFieldName(classNode.name, field.name, field.desc);
        if (remappedName.equals(field.name)) {
            return null;
        }
      
        Deque<String> aliases = new LinkedList<String>();
        aliases.add(remappedName);
        return MixinPreProcessor.findFieldRecursive(classNode, aliases, field.desc);
    }
    
    /**
     * Finds a field in the target class
     * 
     * @param aliases 
     * @param desc
     * @return Target field  or null if not found
     */
    private static FieldNode findFieldRecursive(ClassNode classNode, Deque<String> aliases, String desc) {
        String alias = aliases.poll();
        if (alias == null) {
            return null;
        }
        
        for (FieldNode target : classNode.fields) {
            if (target.name.equals(alias) && target.desc.equals(desc)) {
                return target;
            }
        }

        return MixinPreProcessor.findFieldRecursive(classNode, aliases, desc);
    }
}
