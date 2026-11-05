import com.android.tools.smali.dexlib2.*;
import com.android.tools.smali.dexlib2.iface.*;
import com.android.tools.smali.dexlib2.immutable.ImmutableDexFile;
import java.io.*;
import java.util.*;

/** Replace exactly one vendor class, preserving every other original class definition. */
public final class DexReplace {
    public static void main(String[] args) throws Exception {
        String descriptor=args[3];
        DexFile original=DexFileFactory.loadDexFile(new File(args[0]),Opcodes.getDefault());
        List<ClassDef> keep=new ArrayList<>(); int removed=0;
        for(ClassDef c:original.getClasses()) {if(c.getType().equals(descriptor))removed++;else keep.add(c);}
        if(removed!=1) throw new IllegalStateException("Expected exactly one original class, got "+removed);
        DexFileFactory.writeDexFile(args[1],new ImmutableDexFile(original.getOpcodes(),keep));
        System.out.println("Removed "+descriptor+" from "+args[0]+"; replacement is in "+args[2]);
    }
}
