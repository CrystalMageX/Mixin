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
package org.spongepowered.asm.mixin.injection.struct;

import org.spongepowered.asm.lib.Type;
import org.spongepowered.asm.lib.tree.AbstractInsnNode;
import org.spongepowered.asm.lib.tree.ClassNode;
import org.spongepowered.asm.lib.tree.InsnList;
import org.spongepowered.asm.lib.tree.MethodNode;
import org.spongepowered.asm.mixin.injection.InjectionNodes;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.util.ASMHelper;

/**
 * Information about the current injection target, mainly just convenience
 * rather than passing a bunch of values around.
 */
public class Target implements Comparable<Target> {

    public final ClassNode classNode;

    /**
     * Target method
     */
    public final MethodNode method;
    
    /**
     * Method instructions
     */
    public final InsnList insns;
    
    /**
     * True if the method is static 
     */
    public final boolean isStatic;
    
    /**
     * Method arguments
     */
    public final Type[] arguments;
    
    /**
     * Method argument slots 
     */
    public final int[] argIndices;
    
    /**
     * Return type computed from the method descriptor 
     */
    public final Type returnType;
    
    /**
     * Callback method descriptor based on this target 
     */
    public final String callbackDescriptor;
    
    /**
     * Callback info class
     */
    public final String callbackInfoClass;
    
    /**
     * Nodes targetted by injectors 
     */
    public final InjectionNodes injectionNodes = new InjectionNodes();

    /**
     * Method's (original) MAXS 
     */
    private final int maxStack;
    
    /**
     * Method's original max locals 
     */
    private final int maxLocals;

    /**
     * Make a new Target for the supplied method
     * 
     * @param method target method
     */
    public Target(ClassNode classNode, MethodNode method) {
        this.classNode = classNode;
        this.method = method;
        this.insns = method.instructions;
        this.isStatic = ASMHelper.methodIsStatic(method);
        this.arguments = Type.getArgumentTypes(method.desc);
        this.argIndices = this.calcArgIndices(this.isStatic ? 0 : 1);

        this.returnType = Type.getReturnType(method.desc);
        this.maxStack = method.maxStack;
        this.maxLocals = method.maxLocals;
        this.callbackInfoClass = CallbackInfo.getCallInfoClassName(this.returnType);
        this.callbackDescriptor = String.format("(%sL%s;)V", method.desc.substring(1, method.desc.indexOf(')')), this.callbackInfoClass);
    }
    
    /**
     * Get the original max locals of the method
     * 
     * @return the original max locals value
     */
    public int getMaxLocals() {
        return this.maxLocals;
    }
    
    /**
     * Get the original max stack of the method
     * 
     * @return the original max stack value
     */
    public int getMaxStack() {
        return this.maxStack;
    }
    
    /**
     * Get the current max locals of the method
     * 
     * @return the current max local value
     */
    public int getCurrentMaxLocals() {
        return this.method.maxLocals;
    }
    
    /**
     * Get the current max stack of the method
     * 
     * @return the current max stack value
     */
    public int getCurrentMaxStack() {
        return this.method.maxStack;
    }
    
    /**
     * Allocate a new local variable for the method
     * 
     * @return the allocated local index
     */
    public int allocateLocal() {
        return this.allocateLocals(1);
    }
    
    /**
     * Allocate a number of new local variables for this method, returns the
     * first local variable index of the allocated range
     * 
     * @param locals number of locals to allocate
     * @return the first local variable index of the allocated range
     */
    public int allocateLocals(int locals) {
        int nextLocal = this.method.maxLocals;
        this.method.maxLocals += locals;
        return nextLocal;
    }

    /**
     * Allocate a number of new stack variables for this method, returns the
     * first stack index of the allocated range
     * 
     * @param locals number of locals to allocate
     */
    public void addToLocals(int locals) {
        this.setMaxLocals(this.maxLocals + locals);
    }

    public void setMaxLocals(int maxLocals) {
        if (maxLocals > this.method.maxLocals) {
            this.method.maxLocals = maxLocals;
        }
    }

    public void addToStack(int stack) {
        this.setMaxStack(this.maxStack + stack);
    }

    public void setMaxStack(int maxStack) {
        if (maxStack > this.method.maxStack) {
            this.method.maxStack = maxStack;
        }
    }

    /**
     * Generate an array containing local indexes for the specified args,
     * returns an array of identical size to the supplied array with an
     * allocated local index in each corresponding position
     * 
     * @param args Argument types
     * @param start starting index
     * @return array containing a corresponding local arg index for each member
     *      of the supplied args array
     */
    public int[] generateArgMap(Type[] args, int start) {
        int local = this.maxLocals;
        int[] argMap = new int[args.length];
        for (int arg = start; arg < args.length; arg++) {
            argMap[arg] = local;
            local += args[arg].getSize();
        }
        return argMap;
    }

    private int[] calcArgIndices(int local) {
        int[] argIndices = new int[this.arguments.length];
        for (int arg = 0; arg < this.arguments.length; arg++) {
            argIndices[arg] = local;
            local += this.arguments[arg].getSize();
        }
        return argIndices;
    }
    
    /**
     * Get "simple" callback descriptor (descriptor with only CallbackInfo)
     * 
     * @return generated descriptor
     */
    public String getSimpleCallbackDescriptor() {
        return String.format("(L%s;)V", this.callbackInfoClass);
    }
    
    /**
     * Get the callback descriptor
     * 
     * @param locals Local variable types
     * @param argumentTypes Argument types
     * @return generated descriptor
     */
    public String getCallbackDescriptor(final Type[] locals, Type[] argumentTypes) {
        return this.getCallbackDescriptor(false, locals, argumentTypes, 0, Short.MAX_VALUE);
    }

    /**
     * Get the callback descriptor
     * 
     * @param captureLocals True if the callback is capturing locals
     * @param locals Local variable types
     * @param argumentTypes Argument types
     * @param startIndex local index to start at
     * @param extra extra locals to include
     * @return generated descriptor
     */
    public String getCallbackDescriptor(final boolean captureLocals, final Type[] locals, Type[] argumentTypes, int startIndex, int extra) {
        if (!captureLocals || locals == null) {
            return this.callbackDescriptor;
        }

        String descriptor = this.callbackDescriptor.substring(0, this.callbackDescriptor.indexOf(')'));
        for (int l = startIndex; l < locals.length && extra > 0; l++) {
            if (locals[l] != null) {
                descriptor += locals[l].getDescriptor();
                extra--;
            }
        }

        return descriptor + ")V";
    }
    
    @Override
    public String toString() {
        return String.format("%s::%s%s", this.classNode.name, this.method.name, this.method.desc);
    }

    @Override
    public int compareTo(Target o) {
        if (o == null) {
            return Integer.MAX_VALUE;
        }
        return this.toString().compareTo(o.toString());
    }
    
    /**
     * Replace an instruction in this target with the specified instruction and
     * mark the node as replaced for other injectors
     * 
     * @param location Instruction to replace
     * @param insn Instruction to replace with
     */
    public void replaceNode(AbstractInsnNode location, AbstractInsnNode insn) {
        this.insns.insertBefore(location, insn);
        this.insns.remove(location);
        this.injectionNodes.replace(location, insn);
    }
    
    /**
     * Replace an instruction in this target with the specified instructions and
     * mark the node as replaced with the specified champion node from the list.
     * 
     * @param location Instruction to replace
     * @param champion Instruction which notionally replaces the original insn
     * @param insns Instructions to actually insert (must contain champion)
     */
    public void replaceNode(AbstractInsnNode location, AbstractInsnNode champion, InsnList insns) {
        this.insns.insertBefore(location, insns);
        this.insns.remove(location);
        this.injectionNodes.replace(location, champion);
    }
    
    /**
     * Replace an instruction in this target with the specified instructions and
     * mark the original node as removed
     * 
     * @param location Instruction to replace
     * @param insns Instructions to replace with
     */
    public void replaceNode(AbstractInsnNode location, InsnList insns) {
        this.insns.insertBefore(location, insns);
        this.removeNode(location);
    }
    
    /**
     * Remove the specified instruction from the target and mark it as removed
     * for injections
     * 
     * @param insn instruction to remove
     */
    public void removeNode(AbstractInsnNode insn) {
        this.insns.remove(insn);
        this.injectionNodes.remove(insn);
    }
    
}
