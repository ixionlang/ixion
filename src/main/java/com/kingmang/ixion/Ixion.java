package com.kingmang.ixion;

import com.kingmang.ixion.api.Debugger;
import com.kingmang.ixion.api.IxApi;
import com.kingmang.ixion.api.IxionConstant;
import com.kingmang.ixion.exception.IxException;

import java.io.*;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class Ixion {

    private String entry;
    private boolean helpRequested = false;
    private boolean compileOnly = false;
    private CompilationTarget target = CompilationTarget.JVM_BYTECODE;

    public static void main(String[] args) {
        Ixion cli = new Ixion();
        cli.parseArguments(args);
        cli.run();
    }

    private void parseArguments(String[] args) {
        for (String arg : args) {
            switch (arg) {
                case "-h":
                case "--help":
                    helpRequested = true;
                    break;
                case "--java":
                    target = CompilationTarget.JAVA_SOURCE;
                    break;
                case "--compile-only":
                    compileOnly = true;
                    break;
                default:
                    if (entry == null && !arg.startsWith("-")) {
                        entry = arg;
                    }
                    break;
            }
        }
    }

    private void printHelp() {
        System.out.println("Usage: ixion [OPTIONS] <entry-file>");
        System.out.println("Compile and run an ixion program.\n");
        System.out.println("Options:");
        System.out.println("  -h, --help        Display this help message");
        System.out.println("  --java            Generate Java source code instead of bytecode");
        System.out.println("  --compile-only    Only compile, do not run\n");
    }

    public void executeBytecode(String className) throws IOException, InterruptedException {
        ProcessBuilder processBuilder = new ProcessBuilder(
                "java",
                "--enable-preview",
                "-cp",
                IxionConstant.OUT_DIR + System.getProperty("path.separator") + "target/classes",
                className
        );
        processBuilder.inheritIO();
        Process process = processBuilder.start();

        int status = process.waitFor();
        if (status != 0) System.err.println("Process finished with exit code " + status);
    }

    public void compileJavaToBytecode(String projectRoot, String basePath) throws IOException, InterruptedException {
        String javaFile = Path.of(projectRoot, IxionConstant.OUT_DIR, basePath + ".java").toString();
        String classpath = IxionConstant.OUT_DIR + System.getProperty("path.separator") + "target/classes";

        List<String> command = new ArrayList<>();
        command.add("javac");
        command.add("-d");
        command.add(IxionConstant.OUT_DIR);
        command.add("-cp");
        command.add(classpath);
        command.add(javaFile);

        ProcessBuilder processBuilder = new ProcessBuilder(command);
        processBuilder.inheritIO();
        Process process = processBuilder.start();

        int status = process.waitFor();
        if (status != 0) {
            throw new IOException("Java compilation failed with exit code " + status);
        }
    }

    public void executeJava(String className) throws IOException, InterruptedException {
        ProcessBuilder processBuilder = new ProcessBuilder(
                "java",
                "-cp",
                IxionConstant.OUT_DIR + File.pathSeparator + "target/classes",
                className
        );
        processBuilder.inheritIO();
        Process process = processBuilder.start();

        int status = process.waitFor();
        if (status != 0) System.err.println("Process finished with exit code " + status);
    }

    public void compileAndRunJava(String projectRoot, String basePath, String className)
            throws IOException, InterruptedException {
        Debugger.debug("Compiling Java source code...");
        compileJavaToBytecode(projectRoot, basePath);

        if (!compileOnly) {
            Debugger.debug("Running Java program...");
            executeJava(className);
        }
    }

    public void compileAllJavaFiles(String directory) throws IOException, InterruptedException {
        File dir = new File(directory);
        File[] javaFiles = dir.listFiles((d, name) -> name.endsWith(".java"));

        if (javaFiles != null) {
            List<String> command = new ArrayList<>();
            command.add("javac");
            command.add("-d");
            command.add(IxionConstant.OUT_DIR);
            command.add("-cp");
            command.add(IxionConstant.OUT_DIR + System.getProperty("path.separator") + "target/classes");

            for (File javaFile : javaFiles) {
                command.add(javaFile.getAbsolutePath());
            }

            ProcessBuilder processBuilder = new ProcessBuilder(command);
            processBuilder.inheritIO();
            Process process = processBuilder.start();

            int status = process.waitFor();
            if (status != 0) {
                throw new IOException("Java compilation failed with exit code " + status);
            }
        }
    }

    public void run() {
        if (helpRequested) {
            printHelp();
            return;
        }

        if (entry == null) {
            System.err.println("Error: Entry file is required");
            printHelp();
            System.exit(1);
            return;
        }

        var api = new IxApi();
        String pwd = System.getProperty("user.dir");
        String moduleLocation = Path.of(pwd).toString();

        try {
            String stdDir = Path.of(moduleLocation, "std").toString();
            compileAllJavaFiles(stdDir);

            String classPath = null;
            String basePath = null;

            if (target == CompilationTarget.JAVA_SOURCE) {
                classPath = api.compileToJava(moduleLocation, entry);
                basePath = classPath.replace(".", "/");

                if (!compileOnly) {
                    compileAndRunJava(moduleLocation, basePath, classPath);
                } else {
                    Debugger.debug("Java source generated: " +
                            Path.of(moduleLocation, IxionConstant.OUT_DIR, basePath + ".java"));
                }
            } else {
                classPath = api.compile(moduleLocation, entry);

                if (!compileOnly) {
                    executeBytecode(classPath);
                }
            }

        } catch (FileNotFoundException e) {
            System.err.println("File not found: " + e.getMessage());
            System.exit(2);
        } catch (IxException.CompilerError e) {
            IxApi.exit(e.getMessage(), 1);
        } catch (IOException | InterruptedException e) {
            e.printStackTrace();
            System.exit(3);
        }
    }

    public enum CompilationTarget {
        JVM_BYTECODE,
        JAVA_SOURCE
    }

    // for unit tests:

    public String getCompiledProgramOutput(String entryFileName) throws IOException, InterruptedException {
        StringBuilder output = new StringBuilder();

        if (entryFileName == null || entryFileName.isEmpty()) {
            return "Error: Entry file name is required";
        }

        var api = new IxApi();
        String moduleLocation = System.getProperty("user.dir");

        try {
            String classPath = api.compile(moduleLocation, entryFileName);
            output.append(executeBytecodeAndGetOutput(classPath));

        } catch (Exception e) {
            output.append("Error: ").append(e.getMessage());
        }

        return output.toString();
    }

    private String executeBytecodeAndGetOutput(String className) throws IOException, InterruptedException {
        ProcessBuilder processBuilder = new ProcessBuilder(
                "java",
                "--enable-preview",
                "-cp",
                IxionConstant.OUT_DIR + System.getProperty("path.separator") + "target/classes",
                className
        );

        return executeProcessAndGetOutput(processBuilder);
    }

    private String executeProcessAndGetOutput(ProcessBuilder processBuilder) throws IOException, InterruptedException {
        Process process = processBuilder.start();

        BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
        StringBuilder output = new StringBuilder();
        String line;

        while ((line = reader.readLine()) != null) {
            output.append(line).append(System.lineSeparator());
        }

        int status = process.waitFor();
        if (status != 0) {
            output.append("Process finished with exit code ").append(status);
        }

        return output.toString();
    }
}