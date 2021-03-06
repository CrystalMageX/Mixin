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

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedSet;

import org.spongepowered.asm.lib.tree.AnnotationNode;
import org.spongepowered.asm.lib.tree.ClassNode;
import org.spongepowered.asm.lib.tree.MethodNode;
import org.spongepowered.asm.mixin.MixinEnvironment;
import org.spongepowered.asm.mixin.MixinEnvironment.Option;
import org.spongepowered.asm.mixin.injection.struct.InjectionInfo;

/**
 * Struct for containing target class information during mixin application
 */
class TargetClassContext {
    
    /**
     * Mutable integer
     */
    static class Counter {
        public int value;
    }

    /**
     * Transformer session ID
     */
    private final String sessionId;
    
    /**
     * Target class name 
     */
    private final String className;
    
    /**
     * Target class as tree 
     */
    private final ClassNode classNode;
    
    /**
     * Target class metadata 
     */
    private final ClassInfo classInfo;
    
    /**
     * Mixins to apply 
     */
    private final SortedSet<MixinInfo> mixins;
    
    /**
     * Injector Method descriptor to ID map 
     */
    private final Map<String, Counter> injectorMethodIndices = new HashMap<String, Counter>();

    /**
     * Do not remap injector handler methods (debug option)
     */
    private final boolean disableHandlerRemap;

    /**
     * True once mixins have been applied to this class 
     */
    private boolean applied;

    TargetClassContext(String sessionId, String name, ClassNode classNode, SortedSet<MixinInfo> mixins) {
        this.sessionId = sessionId;
        this.className = name;
        this.classNode = classNode;
        this.classInfo = ClassInfo.fromClassNode(classNode);
        this.mixins = mixins;
        this.disableHandlerRemap = MixinEnvironment.getCurrentEnvironment().getOption(Option.DEBUG_DISABLE_HANDLER_REMAP);
    }
    
    /**
     * Get the transformer session ID
     */
    public String getSessionId() {
        return this.sessionId;
    }
    
    /**
     * Get the internal class name
     */
    public String getName() {
        return this.classNode.name;
    }
    
    /**
     * Get the class name
     */
    public String getClassName() {
        return this.className;
    }

    /**
     * Get the class tree
     */
    public ClassNode getClassNode() {
        return this.classNode;
    }

    /**
     * Get the class methods (from the tree)
     */
    public List<MethodNode> getMethods() {
        return this.classNode.methods;
    }

    /**
     * Get the target class metadata
     */
    public ClassInfo getClassInfo() {
        return this.classInfo;
    }
    
    /**
     * Get the mixins for this target class
     */
    public SortedSet<MixinInfo> getMixins() {
        return this.mixins;
    }

    public String getHandlerName(AnnotationNode annotation, MethodNode method, boolean surrogate) {
        if (this.disableHandlerRemap) {
            return method.name;
        }
        
        String descriptor = String.format("%s%s", method.name, method.desc);
        Counter id = this.injectorMethodIndices.get(descriptor);
        if (id == null) {
            id = new Counter();
            this.injectorMethodIndices.put(descriptor, id);
        } else if (!surrogate) {
            id.value++;
        }
        return String.format("%s$%s$%d", InjectionInfo.getInjectorPrefix(annotation), method.name, id.value);
    }

    /**
     * Apply mixins to this class
     */
    public void applyMixins() {
        if (this.applied) {
            throw new IllegalStateException("Mixins already applied to target class " + this.className);
        }
        this.applied = true;
        MixinApplicator applicator = this.createApplicator();
        applicator.apply(this.mixins);
    }

    private MixinApplicator createApplicator() {
        if (this.classInfo.isInterface()) {
            return new InterfaceMixinApplicator(this);
        }
        return new MixinApplicator(this);
    }
    
}
