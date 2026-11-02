package me.ichun.mods.morph.core;

import net.minecraft.launchwrapper.IClassTransformer;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

/** Preserve vanilla potion/enchantment semantics by exposing the actual creature type. */
public final class MorphTraitTransformer implements IClassTransformer
{
    private static final String HOOK = "me/ichun/mods/morph/common/core/MorphTraits";
    public byte[] transform(String name, String transformedName, byte[] bytes)
    {
        boolean living = "net.minecraft.entity.EntityLivingBase".equals(transformedName);
        boolean player = "net.minecraft.entity.player.EntityPlayer".equals(transformedName);
        if(bytes == null || (!living && !player)) return bytes;
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        boolean patched = false;
        for(MethodNode method : node.methods)
        {
            if(living && (method.name.equals("getCreatureAttribute") || method.name.equals("func_70668_bt")))
            {
                method.instructions.clear();
                method.tryCatchBlocks.clear();
                if(method.localVariables != null) method.localVariables.clear();
                method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
                method.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOK, "creatureAttribute",
                        "(Lnet/minecraft/entity/EntityLivingBase;)Lnet/minecraft/entity/EnumCreatureAttribute;", false));
                method.instructions.add(new InsnNode(Opcodes.ARETURN));
                patched = true;
            }
            if(player && (method.name.equals("setInWeb") || method.name.equals("func_70110_aj")))
            {
                InsnList head = new InsnList();
                LabelNode vanilla = new LabelNode();
                head.add(new VarInsnNode(Opcodes.ALOAD, 0));
                head.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOK, "ignoresWeb",
                        "(Lnet/minecraft/entity/player/EntityPlayer;)Z", false));
                head.add(new JumpInsnNode(Opcodes.IFEQ, vanilla));
                head.add(new InsnNode(Opcodes.RETURN));
                head.add(vanilla);
                head.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));
                method.instructions.insert(head);
                patched = true;
            }
        }
        if(!patched) throw new IllegalStateException("Morph trait patch could not find method in " + transformedName);
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        node.accept(writer);
        return writer.toByteArray();
    }
}
