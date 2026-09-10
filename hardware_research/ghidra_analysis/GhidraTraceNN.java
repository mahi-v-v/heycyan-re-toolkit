import ghidra.app.decompiler.DecompInterface;
import ghidra.app.decompiler.DecompileResults;
import ghidra.app.script.GhidraScript;
import ghidra.program.model.address.Address;
import ghidra.program.model.listing.Function;
import ghidra.program.model.mem.Memory;
import ghidra.program.model.symbol.Reference;
import ghidra.program.model.listing.Instruction;

import java.io.FileWriter;
import java.io.PrintWriter;
import java.util.HashSet;
import java.util.Set;

public class GhidraTraceNN extends GhidraScript {

    @Override
    protected void run() throws Exception {
        String[] args = getScriptArgs();
        if (args.length == 0) {
            println("No output file specified.");
            return;
        }
        String outPath = args[0];
        Memory mem = currentProgram.getMemory();

        try (PrintWriter writer = new PrintWriter(new FileWriter(outPath))) {
            writer.println("// Trace Log for Neural Network DSP Loops");
            
            // We know from earlier that FUN_ram_80882ddc handles the wakeup buffer.
            Address startAddr = currentProgram.getAddressFactory().getAddress("80882ddc");
            if (startAddr == null) {
                writer.println("Could not find address 80882ddc");
                return;
            }
            
            Function startFunc = currentProgram.getFunctionManager().getFunctionAt(startAddr);
            if (startFunc == null) {
                writer.println("Could not find function at 80882ddc");
                return;
            }
            
            writer.println("Found entry function: " + startFunc.getName());
            
            // Dump the decompilation of the entry function to see what it calls
            DecompInterface ifc = new DecompInterface();
            ifc.openProgram(currentProgram);
            DecompileResults res = ifc.decompileFunction(startFunc, 60, monitor);
            if (res != null && res.getDecompiledFunction() != null) {
                writer.println("\n--- Decompiled FUN_ram_80882ddc ---");
                writer.println(res.getDecompiledFunction().getC());
            }
            
            // Search memory for a massive tensor arena in the .bss section (usually zero-initialized blocks)
            writer.println("\n--- Memory Block Analysis (Hunting for Tensor Arena) ---");
            for (ghidra.program.model.mem.MemoryBlock block : mem.getBlocks()) {
                if (!block.isInitialized() || block.getName().contains(".bss")) {
                    writer.println("Block: " + block.getName() + " Size: " + block.getSize() + " bytes");
                }
            }
        }
    }
}
