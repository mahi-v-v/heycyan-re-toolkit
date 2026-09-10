import ghidra.app.decompiler.DecompInterface;
import ghidra.app.decompiler.DecompileResults;
import ghidra.app.script.GhidraScript;
import ghidra.program.model.address.Address;
import ghidra.program.model.listing.Function;
import java.io.FileWriter;
import java.io.PrintWriter;

public class GhidraPhase2 extends GhidraScript {
    @Override
    protected void run() throws Exception {
        String[] args = getScriptArgs();
        if (args.length == 0) {
            println("No output file specified.");
            return;
        }
        String outPath = args[0];

        try (PrintWriter writer = new PrintWriter(new FileWriter(outPath))) {
            writer.println("// Trace Log for Phase 2: Input Shape Analysis");
            
            DecompInterface ifc = new DecompInterface();
            ifc.openProgram(currentProgram);

            String[] targetAddrs = {"80898f18", "80898caa", "80898d0c", "80898ec2"};
            
            for (String addrStr : targetAddrs) {
                Address addr = currentProgram.getAddressFactory().getAddress(addrStr);
                if (addr != null) {
                    Function func = currentProgram.getFunctionManager().getFunctionAt(addr);
                    if (func != null) {
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
        }
    }
}
