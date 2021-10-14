package de.uni_passau.fim.auermich.instrumentation.branchdistance;

import com.google.common.collect.Lists;
import de.uni_passau.fim.auermich.instrumentation.branchdistance.analysis.Analyzer;
import de.uni_passau.fim.auermich.instrumentation.branchdistance.core.Instrumentation;
import de.uni_passau.fim.auermich.instrumentation.branchdistance.dto.MethodInformation;
import de.uni_passau.fim.auermich.instrumentation.branchdistance.utility.Utility;
import de.uni_passau.fim.auermich.instrumentation.branchdistance.xml.ManifestParser;
import lanchon.multidexlib2.BasicDexFileNamer;
import lanchon.multidexlib2.MultiDexIO;
import org.apache.commons.io.FileUtils;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.core.config.Configurator;
import org.jf.dexlib2.iface.ClassDef;
import org.jf.dexlib2.iface.DexFile;
import org.jf.dexlib2.iface.Method;
import org.jf.dexlib2.iface.MethodImplementation;

import java.io.File;
import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Defines the entry point, i.e. the command line interface, of the branch distance
 * instrumentation.
 */
public class BranchDistance {

    private static final Logger LOGGER = LogManager.getLogger(BranchDistance.class);

    // the path to the APK file
    public static String apkPath;

    // the output path of the decoded APK
    public static String decodedAPKPath;

    // the API opcode level defined in the dex header (can be derived automatically)
    public static int OPCODE_API = 28;

    // whether only classes belonging to the app package should be instrumented
    private static boolean onlyInstrumentAUTClasses = false;

    /*
     * Defines the number of additional registers. We require one additional register
     * for storing the unique branch id. Then, we need two additional registers for holding
     * the arguments of if instructions. In addition, we may need two further registers
     * for the shifting of the param registers, since the register type must be consistent
     * within a try-catch block, otherwise the verification process fails.
     */
    public static final int ADDITIONAL_REGISTERS = 3;

    /*
     * We can't instrument methods with more than 256 registers in total,
     * since certain instructions (which we make use of) only allow parameters with
     * register IDs < 256 (some even < 16). As we need two additional register,
     * the register count before instrumentation must be < 255.
     */
    private static final int MAX_TOTAL_REGISTERS = 255;

    /**
     * Processes the command line arguments. The following arguments are supported:
     * <p>
     * 1) The path to the APK file.
     * 2) The flag --only-aut to instrument only classes belonging to the app package (optional).
     *
     * @param args The command line arguments.
     */
    private static void handleArguments(String[] args) {
        assert args.length >= 1 && args.length <= 2;

        apkPath = Objects.requireNonNull(args[0]);
        LOGGER.info("The path to the APK file is: " + apkPath);

        if (args.length == 2) {
            if (args[1].equals("--only-aut")) {
                LOGGER.info("Only instrumenting classes belonging to the app package!");
                onlyInstrumentAUTClasses = true;
            } else {
                LOGGER.info("Argument " + args[1] + " not recognized!");
            }
        }
    }

    /**
     * Defines the command-line entry point. To invoke the branch distance instrumentation,
     * solely the APK is required as input.
     *
     * @param args A single commandline argument specifying the path to the APK file.
     * @throws IOException Should never happen.
     */
    public static void main(String[] args) throws IOException {

        Configurator.setAllLevels(LogManager.getRootLogger().getName(), Level.DEBUG);

        if (args.length < 1 || args.length > 2) {
            LOGGER.info("Wrong number of arguments!");
            LOGGER.info("Usage: java -jar branchDistance.jar <path to the APK file> --only-aut (optional)");
        } else {

            // process command line arguments
            handleArguments(args);

            // describes class names we want to exclude from instrumentation
            Pattern exclusionPattern = Utility.readExcludePatterns();

            // the APK file
            File apkFile = new File(apkPath);

            // decode the APK file
            decodedAPKPath = Utility.decodeAPK(apkFile);

            ManifestParser manifest = new ManifestParser(decodedAPKPath + File.separator + "AndroidManifest.xml");

            // retrieve package name and main activity
            if (!manifest.parseManifest()) {
                LOGGER.warn("Couldn't retrieve MainActivity and/or PackageName!");
                return;
            }

            /*
             * TODO: Directly read from APK file if possible (exception so far). This
             *  should be fixed with the next release, check the github page of (multi)dexlib2.
             *
             * Multidexlib2 provides a merged dex file. So, you don't have to care about
             * multiple dex files at all. When writing this merged dex file to a directory,
             * the dex file is split into multiple dex files such that the method reference
             * constraint is not violated.
             */
            DexFile mergedDex = MultiDexIO.readDexFile(true, new File(decodedAPKPath),
                    new BasicDexFileNamer(), null, null);

            // instrument + write merged dex file to directory
            instrument(mergedDex, exclusionPattern, manifest.getPackageName());

            // add broadcast receiver tag into AndroidManifest
            if (!manifest.addBroadcastReceiverTag(
                    "de.uni_passau.fim.auermich.tracer.Tracer",
                    "STORE_TRACES")) {
                LOGGER.warn("Couldn't insert broadcast receiver tag!");
                return;
            }

            // mark app as debuggable
            if (!manifest.addDebuggableFlag()) {
                LOGGER.warn("Couldn't mark app as debuggable!");
                return;
            }

            // add external storage write permission
            if (!manifest.addPermissionTag("android.permission.WRITE_EXTERNAL_STORAGE")
                    || !manifest.addPermissionTag("android.permission.READ_EXTERNAL_STORAGE")) {
                LOGGER.warn("Couldn't add read/write permission for external storage!");
                return;
            }

            // the output name of the APK
            File outputAPKFile = new File(apkPath.replace(".apk", "-instrumented.apk"));

            // build the APK to the
            Utility.buildAPK(decodedAPKPath, outputAPKFile);

            // remove the decoded APK files
            FileUtils.deleteDirectory(new File(decodedAPKPath));
        }
    }

    /**
     * Instruments the classes respectively methods within a (merged) dex file.
     *
     * @param dexFile          The dexFile containing the classes and methods.
     * @param exclusionPattern A pattern describing classes that should be excluded from instrumentation.
     * @param packageName      The package name of the app.
     * @throws IOException Should never happen.
     */
    private static void instrument(DexFile dexFile, final Pattern exclusionPattern,
                                   final String packageName) throws IOException {

        LOGGER.info("Starting Instrumentation of App!");
        LOGGER.info("Dex version: " + dexFile.getOpcodes().api);
        LOGGER.info("Package Name: " + packageName);

        // set the opcode api level
        OPCODE_API = dexFile.getOpcodes().api;

        // the set of classes we write into the instrumented classes.dex file
        List<ClassDef> classes = Lists.newArrayList();

        for (ClassDef classDef : dexFile.getClasses()) {

            // the class name is part of the method id
            String className = Utility.dottedClassName(classDef.getType());

            // if only classes belonging to the app package should be instrumented
            if (onlyInstrumentAUTClasses && !className.startsWith(packageName)) {
                LOGGER.info("Excluding class: " + className + " from instrumentation!");
                classes.add(classDef);
                continue;
            }

            // exclude certain packages/classes from instrumentation, e.g. android.widget.*
            if ((exclusionPattern != null && exclusionPattern.matcher(className).matches())
                    || Utility.isResourceClass(classDef)
                    || Utility.isBuildConfigClass(classDef)) {
                LOGGER.info("Excluding class: " + className + " from instrumentation!");
                classes.add(classDef);
                continue;
            }

            boolean isActivity = false;
            boolean isFragment = false;

            // check whether the current class is an activity/fragment class
            if (Utility.isActivity(classes, classDef)) {
                isActivity = true;
            } else if (Utility.isFragment(classes, classDef)) {
                isFragment = true;
            }

            // track which activity/fragment lifecycle methods are missing
            Set<String> activityLifeCycleMethods = new HashSet<>(Utility.getActivityLifeCycleMethods());
            Set<String> fragmentLifeCycleMethods = new HashSet<>(Utility.getFragmentLifeCycleMethods());

            // the set of methods included in the instrumented classes.dex
            List<Method> methods = Lists.newArrayList();

            // track whether we modified a method or not
            boolean modifiedMethod = false;

            for (Method method : classDef.getMethods()) {

                String methodSignature = method.toString();

                if (Utility.isJavaObjectMethod(methodSignature) || Utility.isARTMethod(methodSignature)) {
                    /*
                    * We don't instrument methods like hashCode() or equals(), since those methods are not explicitly
                    * called in the most circumstances. Thus, these methods would constitute isolated methods in
                    * the corresponding control flow graph and are excluded for that reason.
                    * NOTE: We need to ensure that the excluded methods here are synced with excluded methods of
                    * the graph construction process, otherwise the branch distance vector may diverge and coverage
                    * calculations might not be accurate!
                     */
                    methods.add(method);
                    continue;
                }

                // track which lifecycle methods are missing, i.e. not overwritten lifecycle methods
                if (isActivity) {
                    String methodName = Utility.getMethodName(methodSignature);
                    if (activityLifeCycleMethods.contains(methodName)) {
                        activityLifeCycleMethods.remove(methodName);
                    }
                } else if (isFragment) {
                    String methodName = Utility.getMethodName(methodSignature);
                    if (fragmentLifeCycleMethods.contains(methodName)) {
                        fragmentLifeCycleMethods.remove(methodName);
                    }
                }

                MethodInformation methodInformation = new MethodInformation(methodSignature, classDef, method, dexFile);
                MethodImplementation methImpl = methodInformation.getMethodImplementation();

                /* We can only instrument methods with a given register count because
                 * our instrumentation code uses instructions that only the usage of
                 * registers with a register ID < MAX_TOTAL_REGISTERS, i.e. the newly
                 * inserted registers aren't allowed to exceed this limit.
                 */
                if (methImpl != null && methImpl.getRegisterCount() < MAX_TOTAL_REGISTERS) {

                    LOGGER.info("Instrumenting method " + method);

                    // determine the new local registers and free register IDs
                    Analyzer.computeRegisterStates(methodInformation, ADDITIONAL_REGISTERS);

                    // determine where we need to instrument
                    methodInformation.setInstrumentationPoints(Analyzer.trackInstrumentationPoints(methodInformation));

                    // determine the method entry points
                    methodInformation.setMethodEntries(Analyzer.trackMethodEntries(methodInformation, dexFile));

                    // determine the method exit points
                    methodInformation.setMethodExits(Analyzer.trackMethodExits(methodInformation));

                    // determine the location of try blocks
                    methodInformation.setTryBlocks(Analyzer.getTryBlocks(methodInformation));

                    // determine the register type of the param registers if the method has param registers
                    if (methodInformation.getParamRegisterCount() > 0) {
                        Analyzer.analyzeParamRegisterTypes(methodInformation, dexFile);
                    }

                    // instrument branches, if statements as well as method entry and exit
                    Instrumentation.modifyMethod(methodInformation, dexFile);
                    modifiedMethod = true;

                    /*
                     * We need to shift param registers by two positions to the left,
                     * e.g. move p1, p2, such that the last (two) param register(s) is/are
                     * free for use. We need two regs for wide types which span over 2 regs.
                     */
                    if (methodInformation.getParamRegisterCount() > 0) {
                        Instrumentation.shiftParamRegisters(methodInformation);
                    }

                    // add instrumented method implementation
                    Utility.addInstrumentedMethod(methods, methodInformation);

                    // write out the branches per method
                    Utility.writeBranches(methodInformation);
                } else {
                    LOGGER.debug("Couldn't instrument method: " + methodSignature);
                    methods.add(method);
                }
            }

            /*
            * We add a dummy implementation for missing activity/fragment lifecycle methods
            * in order to get traces for those methods. Otherwise, the graph lacks markings
            * for those lifecycle methods.
             */
            if (isActivity) {
                LOGGER.info("Missing activity lifecycle methods: " + activityLifeCycleMethods);
                List<ClassDef> superClasses = Utility.getSuperClasses(dexFile, classDef);
                LOGGER.info("Super classes of activity " + className + ": " + superClasses);
                activityLifeCycleMethods.forEach(method ->
                        Instrumentation.addLifeCycleMethod(method, methods, classDef, superClasses));
                modifiedMethod = true;
            } else if (isFragment) {
                LOGGER.info("Missing fragment lifecycle methods: " + fragmentLifeCycleMethods);
                List<ClassDef> superClasses = Utility.getSuperClasses(dexFile, classDef);
                LOGGER.info("Super classes of fragment " + className + ": " + superClasses);
                fragmentLifeCycleMethods.forEach(method ->
                        Instrumentation.addLifeCycleMethod(method, methods, classDef, superClasses));
                modifiedMethod = true;
            }

            if (!modifiedMethod) {
                classes.add(classDef);
            } else {
                // add modified class including its method to the list of classes
                Utility.addInstrumentedClass(classes, methods, classDef);
            }
        }

        // insert tracer class
        ClassDef tracerClass = Utility.loadTracer(OPCODE_API);
        classes.add(tracerClass);

        // write modified (merged) dex file to directory
        Utility.writeMultiDexFile(decodedAPKPath, classes, OPCODE_API);
    }

}
