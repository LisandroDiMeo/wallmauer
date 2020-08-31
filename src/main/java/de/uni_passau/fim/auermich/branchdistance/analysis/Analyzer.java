package de.uni_passau.fim.auermich.branchdistance.analysis;


import com.google.common.collect.Lists;
import de.uni_passau.fim.auermich.branchdistance.branch.Branch;
import de.uni_passau.fim.auermich.branchdistance.branch.ElseBranch;
import de.uni_passau.fim.auermich.branchdistance.branch.IfBranch;
import de.uni_passau.fim.auermich.branchdistance.dto.MethodInformation;
import de.uni_passau.fim.auermich.branchdistance.utility.Utility;
import org.jf.dexlib2.analysis.*;
import org.jf.dexlib2.builder.BuilderInstruction;
import org.jf.dexlib2.builder.BuilderOffsetInstruction;
import org.jf.dexlib2.builder.MutableMethodImplementation;
import org.jf.dexlib2.builder.instruction.BuilderInstruction21t;
import org.jf.dexlib2.builder.instruction.BuilderInstruction22t;
import org.jf.dexlib2.iface.DexFile;
import org.jf.dexlib2.iface.ExceptionHandler;
import org.jf.dexlib2.iface.MethodImplementation;
import org.jf.dexlib2.iface.TryBlock;
import org.jf.dexlib2.iface.instruction.Instruction;
import org.jf.dexlib2.util.MethodUtil;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

public final class Analyzer {


    public static List<AnalyzedInstruction> trackIfInstructions(DexFile dexFile, MethodInformation methodInformation) {

        List<AnalyzedInstruction> ifInstructions = new ArrayList<>();

        MethodAnalyzer analyzer = new MethodAnalyzer(new ClassPath(Lists.newArrayList(new DexClassProvider(dexFile)),
                true, ClassPath.NOT_ART), methodInformation.getMethod(),
                null, false);

        // TODO: can be simplified with stream API
        for (AnalyzedInstruction analyzedInstruction : analyzer.getAnalyzedInstructions()) {

            if (Utility.isBranchingInstruction(analyzedInstruction)) {

                if (analyzedInstruction.getSuccessors().size() > 2) {
                    throw new UnsupportedOperationException("IF statements with > 2 successors are not supported!");
                }

                ifInstructions.add(analyzedInstruction);
            }
        }

        return ifInstructions;
    }

    /**
     * Traverses and collects the branches for the given method. This yields
     * the number of branches per method, whereas duplicates are removed inherently.
     *
     * @param methodInformation Stores all relevant information about a method.
     * @return Returns a set of branches (duplicates eliminated) for the given method.
     */
    public static Set<Branch> trackBranches(MethodInformation methodInformation) {

        MutableMethodImplementation mutableMethodImplementation =
                new MutableMethodImplementation(methodInformation.getMethodImplementation());

        List<BuilderInstruction> instructions = mutableMethodImplementation.getInstructions();

        Set<BuilderInstruction> coveredInstructions = new HashSet<>();
        Set<Branch> branches = new HashSet<>();

        int branchID = 0;

        for (int i = 0; i < instructions.size(); i++) {

            BuilderInstruction instruction = instructions.get(i);

            if (instruction instanceof BuilderInstruction21t
                    || instruction instanceof BuilderInstruction22t && !coveredInstructions.contains(instruction)) {

                // avoid iterating over same instruction multiple times
                coveredInstructions.add(instruction);

                String id = methodInformation.getMethodID() + "->" + branchID;

                // if branch location (uses the instruction/instruction id following the if-instruction)
                Branch ifBranch = new IfBranch(null, id);
                branches.add(ifBranch);
                branchID++;

                // else branch location
                Branch elseBranch = new ElseBranch(null, id);
                branches.add(elseBranch);
                branchID++;
            }
        }
        methodInformation.setBranches(branches);
        return branches;
    }

    /**
     * Tracks the instruction IDs of entry/beginning instructions. A method can have multiple such
     * instructions due to try catch blocks at the beginning of a method.
     *
     * @param methodInformation The method information.
     * @param dexFile           The dexFile containing the method implementation.
     * @return Returns a list of instruction IDs referring to the entry/beginning instructions.
     */
    public static List<Integer> analyzeEntryInstructions(MethodInformation methodInformation, DexFile dexFile) {

        MethodAnalyzer analyzer = new MethodAnalyzer(new ClassPath(Lists.newArrayList(new DexClassProvider(dexFile)),
                true, ClassPath.NOT_ART), methodInformation.getMethod(),
                null, false);

        List<Integer> entryInstructionIDs = new ArrayList<>();

        for (AnalyzedInstruction analyzedInstruction : analyzer.getAnalyzedInstructions()) {
            if (analyzedInstruction.isBeginningInstruction()) {
                entryInstructionIDs.add(analyzedInstruction.getInstructionIndex());
            }
        }

        return entryInstructionIDs;
    }

    /**
     * Determines the new total amount of registers and derives the register IDs of
     * the new registers as well as the free/usable registers.
     *
     * @param methodInformation   Contains the relevant information about a method.
     * @param additionalRegisters The amount of additional registers.
     */
    public static void computeRegisterStates(MethodInformation methodInformation, int additionalRegisters) {

        MethodImplementation methodImplementation = methodInformation.getMethodImplementation();

        int totalRegisters = methodImplementation.getRegisterCount();
        int paramRegisters = MethodUtil.getParameterRegisterCount(methodInformation.getMethod());
        int localRegisters = totalRegisters - paramRegisters;

        // contains the register IDs of the new and free/usable registers
        List<Integer> newRegisters = new ArrayList<>();
        List<Integer> freeRegisters = new ArrayList<>();

        // contains the register IDs of the param registers
        List<Integer> parameterRegisters = new ArrayList<>();

        /*
         * When we increase the number of local registers, the additional
         * registers reside at the end of the local registers, that is:
         *       v0...vN -> v0...vN,vNew1...vNewN
         * The index of the first newly created register resides at
         * the original count of local registers (#localRegisters).
         */
        for (int i = 0; i < additionalRegisters; i++) {
            newRegisters.add(localRegisters + i);
        }
        methodInformation.setNewRegisters(newRegisters);

        /*
         * The idea is to use the last registers for the actual instrumentation by
         * shifting their content into the newly created local registers.
         * This resolves the issue of invoke-range instructions spanning over
         * the newly created local registers.
         * The index of the first usable/free register resides at the original
         * total count of registers (#totalRegisters).
         */
        for (int i = 0; i < additionalRegisters; i++) {
            freeRegisters.add(totalRegisters + i);
        }
        methodInformation.setFreeRegisters(freeRegisters);

        // we need to track the register IDs of the param registers as we shift them later
        for (int p = 0; p < paramRegisters; p++) {
            parameterRegisters.add(localRegisters + p);
        }
        methodInformation.setParamRegisters(parameterRegisters);

        // compute the new count for total/local/param registers
        methodInformation.setTotalRegisterCount(totalRegisters + additionalRegisters);
        methodInformation.setLocalRegisterCount(localRegisters + additionalRegisters);
        // stays changed, no additional param register
        methodInformation.setParamRegisterCount(paramRegisters);
    }

    /**
     * Tracks the first instruction, more precisely its instruction id, of each try and catch block.
     *
     * @param methodInformation The method information.
     * @return Returns a sorted list of instruction ids describing the beginning of each try and catch block.
     */
    public static List<Integer> analyzeTryCatchBlocks(MethodInformation methodInformation) {

        Set<Integer> tryCatchBlockIDs = new HashSet<>();

        MethodImplementation implementation = methodInformation.getMethodImplementation();
        int consumedCodeUnits = 0;

        for (TryBlock<? extends ExceptionHandler> tryBlock : implementation.getTryBlocks()) {

            List<Instruction> instructions = Lists.newArrayList(implementation.getInstructions());

            for (int index = 0; index < instructions.size(); index++) {

                /*
                 * The relation between a code unit and an instruction is as follows:
                 *
                 * code unit | instruction
                 *      0
                 *               instr1
                 *      k
                 *               instr2
                 *      n
                 *
                 * This means to check whether we reached a starting point, e.g., the first instruction
                 * of a try block, we need to compare the code unit counter before consuming the next instruction.
                 *
                 * However, if we want to check some end point, e.g., the end of a try block, we need to compare
                 * the code unit counter after the consumption of the next instruction.
                 */

                // the starting point is before the actual instruction
                if (consumedCodeUnits == tryBlock.getStartCodeAddress()) {
                    // reached the beginning of the try block
                    tryCatchBlockIDs.add(index);
                }
                consumedCodeUnits += instructions.get(index).getCodeUnits();
            }

            // iterate over attached catch blocks
            tryBlock.getExceptionHandlers().forEach(h -> {

                /*
                 * The (absolute) position of the catch block expressed in terms of code units. The catch
                 * block starts after n-th code units. So, we need to map an instruction to its
                 * size (code units) and count them.
                 */
                AtomicInteger ctrCodeUnits = new AtomicInteger(0);

                for (int index = 0; index < instructions.size(); index++) {
                    if (ctrCodeUnits.get() == h.getHandlerCodeAddress()) {
                        // reached the beginning of the catch block
                        tryCatchBlockIDs.add(index);
                        break;
                    }
                    ctrCodeUnits.set(ctrCodeUnits.get() + instructions.get(index).getCodeUnits());
                }
            });
        }

        // ensure ascending order
        return tryCatchBlockIDs.stream().sorted().collect(Collectors.toList());
    }

    /**
     * Determines the register types of the parameter registers at the method entry.
     *
     * @param methodInformation Stores relevant information about a method.
     * @param dexFile           The un-instrumented dex file.
     */
    public static void analyzeParamRegisterTypes(MethodInformation methodInformation, DexFile dexFile) {

        MethodAnalyzer analyzer = new MethodAnalyzer(new ClassPath(Lists.newArrayList(new DexClassProvider(dexFile)),
                true, ClassPath.NOT_ART), methodInformation.getMethod(),
                null, false);

        Map<Integer, RegisterType> registerTypes = new HashMap<>();

        // we want the register type at the method head, that is before the first instruction
        AnalyzedInstruction instruction = analyzer.getAnalyzedInstructions().get(0);

        for (int registerID : methodInformation.getParamRegisters()) {
            registerTypes.put(registerID, instruction.getPreInstructionRegisterType(registerID));
        }

        methodInformation.setParamRegisterTypeMap(Optional.of(registerTypes));
    }
}
