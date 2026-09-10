from ghidra.app.decompiler import DecompInterface
from ghidra.util.task import ConsoleTaskMonitor
import string

monitor = ConsoleTaskMonitor()
ifc = DecompInterface()
ifc.openProgram(currentProgram)
out_path = getScriptArgs()[0]

mem = currentProgram.getMemory()
funcs = set()

def find_string_refs(search_str):
    search_bytes = [ord(c) for c in search_str]
    addrs = mem.findBytes(currentProgram.getMinAddress(), search_bytes, None, True, monitor)
    for addr in addrs:
        for ref in getReferencesTo(addr):
            func = currentProgram.getFunctionManager().getFunctionContaining(ref.getFromAddress())
            if func:
                funcs.add(func)

find_string_refs("cyan")
find_string_refs("wakeup")

with open(out_path, 'w') as f:
    f.write("// Decompiled output for: " + currentProgram.getName() + "\n")
    if not funcs:
        f.write("// No functions found referencing target strings.\n")
    for func in funcs:
        f.write("\n// ----------------------------------------\n")
        f.write("// Function: %s\n" % func.getName())
        f.write("// ----------------------------------------\n")
        res = ifc.decompileFunction(func, 60, monitor)
        if res and res.getDecompiledFunction():
            f.write(res.getDecompiledFunction().getC() + "\n")
        else:
            f.write("// Failed to decompile\n")
