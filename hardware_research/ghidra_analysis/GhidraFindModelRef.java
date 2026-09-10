import ghidra.app.decompiler.DecompInterface;
import ghidra.app.decompiler.DecompileResults;
import ghidra.app.script.GhidraScript;
import ghidra.program.model.address.Address;
import ghidra.program.model.address.AddressSet;
import ghidra.program.model.listing.Function;
import ghidra.program.model.listing.Instruction;
import ghidra.program.model.listing.InstructionIterator;
import ghidra.program.model.mem.Memory;
import ghidra.program.model.mem.MemoryBlock;
import ghidra.program.model.symbol.Reference;
import ghidra.program.model.symbol.ReferenceIterator;

import java.io.FileWriter;
import java.io.PrintWriter;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.Queue;
import java.util.Set;

/**
 * GhidraFindModelRef.java
 *
 * Starting from the known wake-word entry point FUN_ram_80882ddc, perform a
 * breadth-first traversal of its call tree (up to depth 6) and collect every
 * data reference that lands inside the .data_unsaved segment
 * (0x80900e38 – 0x809e1c80).
 *
 * This narrows the list of candidate model weight addresses down from the
 * 31 regions found by scan_dense_regions.py to the exact pointers the
 * wake-word inference code actually reads.
 *
 * Run from: hardware_research/ghidra_analysis/  (clean script dir)
 * Usage:
 *   analyzeHeadless.bat "<proj_dir>" "ghidra_proj"
 *     -process riscv
 *     -scriptPath "<proj_dir>/hardware_research/ghidra_analysis"
 *     -postScript GhidraFindModelRef.java
 *             "<proj_dir>/hardware_research/ghidra_analysis/model_refs.txt"
 */
public class GhidraFindModelRef extends GhidraScript {

    // .data_unsaved bounds (from readelf)
    private static final long DATA_UNSAVED_START = 0x80900e38L;
    private static final long DATA_UNSAVED_END   = 0x809e1c80L;

    @Override
    protected void run() throws Exception {
        String[] args = getScriptArgs();
        if (args.length == 0) {
            println("Usage: GhidraFindModelRef <output_file>");
            return;
        }
        String outPath = args[0];

        // ── Entry point ──────────────────────────────────────────────
        Address entryAddr = currentProgram.getAddressFactory()
                                          .getAddress("80882ddc");
        Function entryFn = getFunctionAt(entryAddr);
        if (entryFn == null) {
            println("Could not find FUN_ram_80882ddc — ensure the binary is analysed.");
            return;
        }

        DecompInterface ifc = new DecompInterface();
        ifc.openProgram(currentProgram);

        // ── BFS call-tree traversal (depth ≤ 6) ─────────────────────
        Set<Function> visited = new HashSet<>();
        Queue<Function> queue = new LinkedList<>();
        queue.add(entryFn);
        visited.add(entryFn);

        // Collect all data refs into .data_unsaved that we find
        // Map: function name → set of addresses referenced
        java.util.Map<String, Set<Long>> dataRefs = new java.util.LinkedHashMap<>();

        int depthLimit = 6;
        // Use a second queue to track depth
        Queue<Integer> depthQueue = new LinkedList<>();
        depthQueue.add(0);

        while (!queue.isEmpty()) {
            Function fn = queue.poll();
            int depth = depthQueue.poll();
            if (depth > depthLimit) continue;

            Set<Long> refs = new HashSet<>();

            // Scan every instruction in this function for data references
            // into .data_unsaved
            InstructionIterator instIter = currentProgram.getListing()
                    .getInstructions(fn.getBody(), true);
            while (instIter.hasNext() && !monitor.isCancelled()) {
                Instruction inst = instIter.next();
                for (Reference ref : inst.getReferencesFrom()) {
                    long toAddr = ref.getToAddress().getOffset();
                    if (toAddr >= DATA_UNSAVED_START && toAddr < DATA_UNSAVED_END) {
                        refs.add(toAddr);
                    }
                }
            }

            if (!refs.isEmpty()) {
                dataRefs.put(fn.getName() + " @ " + fn.getEntryPoint(), refs);
            }

            // Enqueue callees
            if (depth < depthLimit) {
                for (Function callee : fn.getCalledFunctions(monitor)) {
                    if (!visited.contains(callee)) {
                        visited.add(callee);
                        queue.add(callee);
                        depthQueue.add(depth + 1);
                    }
                }
            }
        }

        // ── Write results ─────────────────────────────────────────────
        try (PrintWriter w = new PrintWriter(new FileWriter(outPath))) {
            w.println("// GhidraFindModelRef — Data references into .data_unsaved");
            w.println("// .data_unsaved: 0x80900e38 – 0x809e1c80 (897 KB)");
            w.println("// Functions traversed in BFS from FUN_ram_80882ddc (depth ≤ 6):");
            w.println("// Total functions visited: " + visited.size());
            w.println();

            if (dataRefs.isEmpty()) {
                w.println("// No direct data references into .data_unsaved found.");
                w.println("// The model pointer is likely loaded indirectly via a register chain.");
                w.println("// Fallback: use scan_dense_regions.py candidates for zeroing.");
            } else {
                for (java.util.Map.Entry<String, Set<Long>> e : dataRefs.entrySet()) {
                    w.println("// ─── " + e.getKey() + " ───");
                    for (long addr : e.getValue()) {
                        long fileOffset = addr - 0x80900e38L + 0xa1e38L;
                        w.printf("//   RAM 0x%08x  →  file offset 0x%x%n", addr, fileOffset);
                    }
                    w.println();
                }
            }

            // Also decompile entry function for reference
            DecompileResults res = ifc.decompileFunction(entryFn, 60, monitor);
            if (res != null && res.getDecompiledFunction() != null) {
                w.println("\n// ─── Decompiled entry: FUN_ram_80882ddc ───");
                w.println(res.getDecompiledFunction().getC());
            }
        }

        println("Done. Results written to: " + outPath);
    }
}
