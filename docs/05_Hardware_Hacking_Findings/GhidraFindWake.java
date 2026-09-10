import ghidra.app.decompiler.DecompInterface;
import ghidra.app.decompiler.DecompileResults;
import ghidra.app.script.GhidraScript;
import ghidra.program.model.address.Address;
import ghidra.program.model.listing.Function;
import ghidra.program.model.mem.Memory;
import ghidra.program.model.symbol.Reference;

import java.io.FileWriter;
import java.io.PrintWriter;
import java.util.HashSet;
import java.util.Set;

public class GhidraFindWake extends GhidraScript {
    private Set<Function> funcs = new HashSet<>();

    @Override
    protected void run() throws Exception {
        String[] args = getScriptArgs();
        if (args.length == 0) {
            println("No output file specified.");
            return;
        }
        String outPath = args[0];
        Memory mem = currentProgram.getMemory();
        
        findStringRefs("cyan", mem);
        findStringRefs("wakeup", mem);

        DecompInterface ifc = new DecompInterface();
        ifc.openProgram(currentProgram);

        try (PrintWriter writer = new PrintWriter(new FileWriter(outPath))) {
            writer.println("// Decompiled output for: " + currentProgram.getName());
            if (funcs.isEmpty()) {
                writer.println("// No functions found referencing target strings.");
            }
            for (Function func : funcs) {
                writer.println("\n// ----------------------------------------");
                writer.println("// Function: " + func.getName());
                writer.println("// ----------------------------------------");
                DecompileResults res = ifc.decompileFunction(func, 60, monitor);
                if (res != null && res.getDecompiledFunction() != null) {
                    writer.println(res.getDecompiledFunction().getC());
                } else {
                    writer.println("// Failed to decompile");
                }
            }
        }
    }

    private void findStringRefs(String searchStr, Memory mem) {
        byte[] searchBytes = searchStr.getBytes();
        Address addr = currentProgram.getMinAddress();
        while (addr != null) {
            addr = mem.findBytes(addr, searchBytes, null, true, monitor);
            if (addr == null) break;
            
            Reference[] refs = getReferencesTo(addr);
            for (Reference ref : refs) {
                Function func = currentProgram.getFunctionManager().getFunctionContaining(ref.getFromAddress());
                if (func != null) {
                    funcs.add(func);
                }
            }
            try {
                addr = addr.add(1);
            } catch (Exception e) {
                break;
            }
        }
    }
}
