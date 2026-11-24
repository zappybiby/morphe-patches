import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.nio.file.Files;
import java.nio.file.Path;

/** Wrap the one public patch getter that remains after shrinking to Add to queue. */
class WrapEntry {
    public static void main(String[] args) throws Exception {
        var path = Path.of(args[0]);
        var reader = new ClassReader(Files.readAllBytes(path));
        var writer = new ClassWriter(reader, 0);
        String patch = "Lapp/morphe/patcher/patch/Patch;";
        reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] errors) {
                var method = super.visitMethod(access, name, desc, signature, errors);
                if (!name.equals("getAddToQueuePatch") || !desc.equals("()" + patch)) return method;
                return new MethodVisitor(Opcodes.ASM9, method) {
                    @Override
                    public void visitInsn(int opcode) {
                        if (opcode == Opcodes.ARETURN) {
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, "app/zappybiby/patches/queue/QueueTest",
                                    "wrap", "(" + patch + ")" + patch, false);
                        }
                        super.visitInsn(opcode);
                    }
                };
            }
        }, 0);
        Files.write(path, writer.toByteArray());
    }
}
