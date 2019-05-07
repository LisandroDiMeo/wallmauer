package de.uni_passau.fim.utility;

import de.uni_passau.fim.branchcoverage.RegisterInformation;
import org.jf.dexlib2.DexFileFactory;
import org.jf.dexlib2.Opcodes;
import org.jf.dexlib2.builder.BuilderInstruction;
import org.jf.dexlib2.builder.MutableMethodImplementation;
import org.jf.dexlib2.builder.instruction.BuilderInstruction3rc;
import org.jf.dexlib2.iface.ClassDef;
import org.jf.dexlib2.iface.DexFile;
import org.jf.dexlib2.iface.Method;
import org.jf.dexlib2.iface.MethodImplementation;

import javax.annotation.Nonnull;
import java.io.*;
import java.net.URISyntaxException;
import java.util.AbstractSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

public final class Utility {

    public static final String EXCLUSION_PATTERN_FILE = "exclude.txt";
    public static final String OUTPUT_BRANCHES_FILE = "branches.txt";

    private Utility() {
        throw new UnsupportedOperationException("Utility class!");
    }

    /**
     * Checks whether we can find the 'MainActivity' class in the set of given classes.
     *
     * @param classes The set of classes contained in the classes.dex file.
     * @param mainActivity The name of the 'MainActivity'.
     * @return Returns @{code true} if the 'MainActivity is included, otherwise {@code false}.
     */
    public static boolean containsMainActivity(Set<? extends ClassDef> classes, String mainActivity) {

        for (ClassDef classDef: classes) {
            if (classDef.toString().equals(mainActivity)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Checks whether the 'MainActivity' class declares an 'onDestroy' method.
     *
     * @param mainClass The 'MainActivity' class.
     * @return Returns @{code true} if the onDestroy method is declared, otherwise {@code false}.
     */
    public static boolean containsOnDestroy(ClassDef mainClass) {

        for (Method method : mainClass.getMethods()) {
            if (method.getName().equals("onDestroy")) {
                return true;
            }
        }
        return false;
    }

    public static boolean isMainActivity(ClassDef classDef, String mainActivity) {
        return classDef.toString().equals(mainActivity);
    }

    public static boolean isOnDestroy(Method method) {
        return method.getName().equals("onDestroy");
    }

    /**
     * Writes the number of branches for each class to the given file.
     * Classes without any branches are omitted.
     *
     * @param className The name of the class.
     * @param branchCounter The number of branches for a certain class.
     * @throws FileNotFoundException Should never be thrown.
     */
    public static void writeBranches(String className, int branchCounter) throws FileNotFoundException {

        File file = new File(OUTPUT_BRANCHES_FILE);
        OutputStream outputStream = new FileOutputStream(file, true);
        PrintStream printStream = new PrintStream(outputStream);

        if (branchCounter != 0) {
            // we have to save our branchCounter for the later evaluation
            printStream.println(className + ": " + branchCounter);
            printStream.flush();
        }
        printStream.close();
    }

    /**
     * Transforms a class name containing '/' into a class name with '.'
     * instead, and removes the leading 'L' as well as the ';' at the end.
     *
     * @param className The class name which should be transformed.
     * @return The transformed class name.
     */
    public static String dottedClassName(String className) {
        className = className.substring(className.indexOf('L') + 1, className.indexOf(';'));
        className = className.replace('/', '.');
        return className;
    }

    /**
     * Generates patterns of classes which should be excluded from the instrumentation.
     *
     * @return The pattern representing classes that should not be instrumented.
     * @throws IOException        If the file containing excluded classes is not available.
     * @throws URISyntaxException If the file is not present.
     */
    public static Pattern readExcludePatterns() throws IOException, URISyntaxException {

        ClassLoader classLoader = ClassLoader.getSystemClassLoader();
        InputStream inputStream = classLoader.getResourceAsStream(EXCLUSION_PATTERN_FILE);

        if (inputStream == null) {
            System.out.println("Couldn't find exlcusion file!");
            return null;
        }

        BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream));
        String line;
        StringBuilder builder = new StringBuilder();
        boolean first = true;
        while ((line = reader.readLine()) != null) {
            if (first)
                first = false;
            else
                builder.append("|");
            builder.append(line);
        }
        reader.close();
        return Pattern.compile(builder.toString());
    }

    public static void writeToDexFile(String filePath, List<ClassDef> classes, int opCode) throws IOException {

        DexFileFactory.writeDexFile(filePath, new DexFile() {
            @Nonnull
            @Override
            public Set<? extends ClassDef> getClasses() {
                return new AbstractSet<ClassDef>() {
                    @Nonnull
                    @Override
                    public Iterator<ClassDef> iterator() {
                        return classes.iterator();
                    }

                    @Override
                    public int size() {
                        return classes.size();
                    }
                };
            }

            @Nonnull
            @Override
            public Opcodes getOpcodes() {
                return Opcodes.forApi(opCode);
            }
        });
    }

    /**
     * Increases the register directive of the method, i.e. the .register statement at the method head
     * according to the number specified by {@param newRegisterCount}.
     *
     * @param mutableImplementation The implementation representing the method.
     * @param newRegisterCount      The new amount of registers the method should have.
     * @throws NoSuchFieldException   Should never happen - a byproduct of reflection.
     * @throws IllegalAccessException Should never happen - a byproduct of reflection.
     */
    public static void increaseMethodRegisterCount(MutableMethodImplementation mutableImplementation, int newRegisterCount) {

        try {
            java.lang.reflect.Field f = mutableImplementation.getClass().getDeclaredField("registerCount");
            f.setAccessible(true);
            f.set(mutableImplementation, newRegisterCount);
        } catch (NoSuchFieldException | IllegalAccessException e) {
            e.printStackTrace();
        }
    }

    public static MethodImplementation replaceRegisterIDs(MethodImplementation implementation, RegisterInformation information,
                                                          Set<BuilderInstruction> insertedInstructions) {

        MutableMethodImplementation mutableMethodImplementation = new MutableMethodImplementation(implementation);
        int firstUsableRegister = information.getUsableRegisters().get(0);
        int secondUsableRegister = information.getUsableRegisters().get(1);
        int firstNewLocalRegister = information.getNewLocalRegisters().get(0);
        int secondNewLocalRegister = information.getNewLocalRegisters().get(1);

        for (BuilderInstruction instruction : mutableMethodImplementation.getInstructions()) {

            // TODO: can't handle this unfortunately!!!!
            // TODO: reserve for all param-registers one additional local reg + if needed local regs for traces
            // TODO: then mv all param regs to the new local regs at method head, replace each param reg id with new local reg id

            System.out.println(instruction.getLocation().getIndex());
            System.out.println(instruction.getLocation().getCodeAddress());
            System.out.println(instruction.getCodeUnits());
            System.out.println(instruction.getFormat());
            System.out.println(instruction.getOpcode());
            System.out.println(System.lineSeparator());


            // instructions that we inserted shouldn't be modified again!
            boolean contained = false;

            // TODO: implement some working contains method, i.e. implement hashCode + equals for class BuilderInstruction
            for (BuilderInstruction builderInstruction : insertedInstructions) {
                if (instruction.getLocation().getIndex() == builderInstruction.getLocation().getIndex()
                        && instruction.getLocation().getCodeAddress() == builderInstruction.getLocation().getCodeAddress()
                        && instruction.getCodeUnits() == builderInstruction.getCodeUnits()
                        && instruction.getFormat().equals(builderInstruction.getFormat())
                        && instruction.getOpcode().equals(builderInstruction.getOpcode())) {
                    contained = true;
                    break;
                }

            }
            if (contained) {
                System.out.println("Skipping instruction: " + instruction.getOpcode());
                continue;
            }

            // those invoke range instructions require a special treatment, since they don't have fields containing the registers
            if (instruction instanceof BuilderInstruction3rc) {

                // those instructions store the number of registers (var registerCount) and the first register of these range (var startRegister)
                /*
                int registerStart = ((BuilderInstruction3rc) instruction).getStartRegister();
                java.lang.reflect.Field f = instruction.getClass().getDeclaredField("startRegister");
                if (registerStart >= registerNumber) {
                    f.setAccessible(true);
                    f.set(instruction, registerStart + shift);
                }
                */
                return mutableMethodImplementation;
            }

            java.lang.reflect.Field[] fields = instruction.getClass().getDeclaredFields();

            for (java.lang.reflect.Field field : fields) {
                // all fields are labeled registerA - registerG
                if (field.getName().startsWith("register") && !field.getName().equals("registerCount")) {
                    field.setAccessible(true);
                    try {
                        int value = field.getInt(instruction);

                        // replace first usable with first local
                        if (value == firstUsableRegister) {
                            field.set(instruction, firstNewLocalRegister);
                        }

                        // replace second usable with second local
                        if (value == secondUsableRegister) {
                            field.set(instruction, secondNewLocalRegister);
                        }

                    } catch (IllegalAccessException e) {
                        e.printStackTrace();
                    }
                }
            }
        }
        return mutableMethodImplementation;
    }

    /**
     * Increasing the amount of registers for a method requires shifting
     * certain registers back to their original position. In particular, all
     * registers, which register id is bigger or equal than the new specified
     * register count {@param registerNumber}, need to be shifted (increased) by the amount of the newly
     * created registers. Especially, originally param registers are affected
     * by increasing the register count, and would be treated now as a local register
     * without re-ordering (shifting).
     *
     * @param instruction    The instruction that is currently inspected.
     * @param registerNumber Specifies a lower limit for registers, which need to be considered.
     * @throws NoSuchFieldException   Should never happen, constitutes a byproduct of using reflection.
     * @throws IllegalAccessException Should never happen, constitutes a byproduct of using reflection.
     */
    public static void reOrderRegister(BuilderInstruction instruction, int registerNumber, int shift)
            throws NoSuchFieldException, IllegalAccessException {

        // those invoke range instructions require a special treatment, since they don't have fields containing the registers
        if (instruction instanceof BuilderInstruction3rc) {

            // those instructions store the number of registers (var registerCount) and the first register of these range (var startRegister)
            int registerStart = ((BuilderInstruction3rc) instruction).getStartRegister();
            java.lang.reflect.Field f = instruction.getClass().getDeclaredField("startRegister");
            if (registerStart >= registerNumber) {
                f.setAccessible(true);
                f.set(instruction, registerStart + shift);
            }
            return;
        }

        java.lang.reflect.Field[] fields = instruction.getClass().getDeclaredFields();

        for (java.lang.reflect.Field field : fields) {
            // all fields are labeled registerA - registerG
            if (field.getName().startsWith("register") && !field.getName().equals("registerCount")) {
                field.setAccessible(true);
                // System.out.println(field.getName());
                try {
                    int value = field.getInt(instruction);

                    if (value >= registerNumber)
                        field.set(instruction, value + shift);

                } catch (IllegalAccessException e) {
                    e.printStackTrace();
                }
            }
        }
    }

}
