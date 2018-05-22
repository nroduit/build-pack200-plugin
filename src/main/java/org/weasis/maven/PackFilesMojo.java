package org.weasis.maven;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.jar.Pack200;
import java.util.jar.Pack200.Packer;
import java.util.jar.Pack200.Unpacker;
import java.util.zip.GZIPOutputStream;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.codehaus.plexus.util.FileUtils;
import org.codehaus.plexus.util.IOUtil;
import org.codehaus.plexus.util.StringUtils;

/**
 * Goal capable of compressing a list of jar files with the pack200 tool.
 * 
 * @goal packFiles
 * 
 * @phase package
 */
public class PackFilesMojo extends AbstractMojo {

    private static final String ORIGINAL_JAR = ".original.jar";
    /**
     * The base directory to scan for JAR files using Ant-like inclusion/exclusion patterns.
     * 
     * @parameter property="pack200.archiveDirectory"
     * @required
     */
    private File archiveDirectory;
    /**
     * The directory where pack200 files are written.
     * 
     * @parameter property="pack200.outputDirectory"
     */
    private File outputDirectory;
    /**
     * The Ant-like inclusion patterns used to select JAR files to process. The patterns must be relative to the
     * directory given by the parameter {@link #archiveDirectory}. By default, the pattern
     * <code>&#42;&#42;/&#42;.?ar</code> is used.
     * 
     * @parameter
     */
    private String[] includes = { "**/*.?ar" };

    /**
     * The Ant-like exclusion patterns used to exclude JAR files from processing. The patterns must be relative to the
     * directory given by the parameter {@link #archiveDirectory}.
     * 
     * @parameter
     */
    private String[] excludes = {};

    /**
     * @parameter default-value="false"
     */
    private boolean normalizeOnly;

    /**
     * Will jars be signed. If null normalizeOnly is not executed.
     * 
     * @parameter
     */
    private String signed;

    /**
     * @parameter default-value="7"
     */
    private String effort;

    /**
     * @parameter default-value="-1"
     */
    private String segmentLimit;

    /**
     * @parameter default-value="false"
     */
    private String keepFileOrder;

    /**
     * @parameter default-value="latest"
     */
    private String modificationTime;

    /**
     * @parameter default-value="false"
     */
    private String deflateHint;

    /**
     * @parameter
     */
    private String[] stripCodeAttributes = { "SourceFile", "LineNumberTable", "LocalVariableTable", "Deprecated" };

    /**
     * @parameter default-value="true"
     */
    private boolean failOnUnknownAttributes;

    /**
     * @parameter default-value="true"
     */
    private boolean compress;

    @Override
    public void execute() throws MojoExecutionException {

        getLog().debug("starting pack");

        Packer packer = Pack200.newPacker();
        Unpacker unpacker = null;

        if (normalizeOnly) {
            if (signed == null) {
                // no need to normalized
                return;
            }
            unpacker = Pack200.newUnpacker();
        }

        Map<String, String> packerProps = packer.properties();
        packerProps.put(Packer.EFFORT, effort);
        packerProps.put(Packer.SEGMENT_LIMIT, segmentLimit);
        packerProps.put(Packer.KEEP_FILE_ORDER, keepFileOrder);
        packerProps.put(Packer.MODIFICATION_TIME, modificationTime);
        packerProps.put(Packer.DEFLATE_HINT, deflateHint);

        if (stripCodeAttributes != null) {
            for (String attributeName : stripCodeAttributes) {
                getLog().debug("stripping " + attributeName);
                packerProps.put(Packer.CODE_ATTRIBUTE_PFX + attributeName, Packer.STRIP);
            }
        }

        if (failOnUnknownAttributes) {
            packerProps.put(Packer.UNKNOWN_ATTRIBUTE, Packer.ERROR);
        }

        if (archiveDirectory != null) {
            if (outputDirectory == null) {
                outputDirectory = archiveDirectory;
            }
            String archivePath = archiveDirectory.getAbsolutePath();
            if (!archivePath.endsWith(File.separator)) {
                archivePath = archivePath + File.separator;
            }
            int inputPathLength = archivePath.length();
            String includeList = (includes != null) ? StringUtils.join(includes, ",") : null;
            String excludeList = (excludes != null) ? StringUtils.join(excludes, ",") : null;

            List<File> jarFiles;
            try {
                jarFiles = FileUtils.getFiles(archiveDirectory, includeList, excludeList);
            } catch (IOException e) {
                throw new MojoExecutionException("Failed to scan archive directory for JARs: " + e.getMessage(), e);
            }
            getLog().info(normalizeOnly ? "normalizing" : "packing" + " " + jarFiles.size() + " files");
            for (Iterator<File> it = jarFiles.iterator(); it.hasNext();) {
                File jarFile = it.next();
                String filename = jarFile.getAbsolutePath().substring(inputPathLength);
                if (filename.endsWith(".pack")) {
                    filename = filename.substring(0, filename.length() - 5);
                } else if (filename.endsWith(".pack.gz")) {
                    filename = filename.substring(0, filename.length() - 8);
                }
                processArchive(filename, packer, unpacker);
            }
        }

    }

    private void processArchive(String filename, Packer packer, Unpacker unpacker) throws MojoExecutionException {
        File jarFile = new File(archiveDirectory, filename);
        File packFile = new File(outputDirectory, filename + ".pack");
        File zipFile = new File(outputDirectory, filename + ".pack.gz");
        try {

            try (JarFile jar = new JarFile(jarFile); FileOutputStream fos = new FileOutputStream(packFile)) {
                getLog().debug("packing " + filename + " to " + packFile);
                packer.pack(jar, fos);
                getLog().debug("closing handles ...");
            }

            if (normalizeOnly) {
                getLog().debug("unpacking " + packFile + " to " + filename);
                Files.move(jarFile.toPath(), Paths.get(jarFile.getPath() + ORIGINAL_JAR),
                    StandardCopyOption.REPLACE_EXISTING);
                try (JarOutputStream origJarStream = new JarOutputStream(new FileOutputStream(jarFile))) {
                    unpacker.unpack(packFile, origJarStream);
                    getLog().debug("closing handles...");
                }

                getLog().debug("unpacked file");
            } else {
                if (compress) {
                    getLog().debug("compressing " + packFile + " to " + zipFile);
                    try (FileInputStream in =new FileInputStream(packFile); GZIPOutputStream zipOut = new GZIPOutputStream(new FileOutputStream(zipFile))) {
                        IOUtil.copy(in, zipOut);
                    }
                    Files.delete(packFile.toPath());
                    File original = new File(jarFile.getPath() + ORIGINAL_JAR);
                    if (original.canRead()) {
                        Files.move(original.toPath(), jarFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
                    }
                }
            }

            getLog().debug("finished " + (normalizeOnly ? "normalizing" : "packing") + " " + packFile);

        } catch (IOException e) {
            throw new MojoExecutionException("Failed to pack jar.", e);
        }
    }
}
