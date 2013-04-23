/*
 * Copyright 2013 The jdeb developers.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.vafer.jdeb.ant;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;

import org.apache.commons.io.IOUtils;
import org.apache.tools.ant.BuildException;
import org.apache.tools.ant.taskdefs.MatchingTask;
import org.apache.tools.ant.taskdefs.Tar;
import org.apache.tools.ant.types.FileSet;
import org.vafer.jdeb.Compression;
import org.vafer.jdeb.DataProducer;
import org.vafer.jdeb.Processor;
import org.vafer.jdeb.changes.TextfileChangesProvider;
import org.vafer.jdeb.debian.BinaryPackageControlFile;
import org.vafer.jdeb.debian.ChangesFile;
import org.vafer.jdeb.producers.DataProducerFileSet;
import org.vafer.jdeb.signing.PGPSigner;

/**
 * TODO generalize with DebMaker
 *
 * AntTask for creating debian archives.
 * Even supports signed changes files.
 *
 * @author Torsten Curdt
 */
public class DebAntTask extends MatchingTask {

    /**
     * The Debian package produced
     */
    private File deb;

    /**
     * The directory containing the control files to build the package
     */
    private File control;

    /**
     * The file containing the PGP keys
     */
    private File keyring;

    /**
     * The key to use in the keyring
     */
    private String key;

    /**
     * The passphrase for the key to sign the changes file
     */
    private String passphrase;

    /**
     * The file to read the changes from
     */
    private File changesIn;

    /**
     * The file where to write the changes to
     */
    private File changesOut;

    /**
     * The file where to write the changes of the changes input to
     */
    private File changesSave;

    /**
     * The compression method used for the data file (none, gzip or bzip2)
     */
    private String compression = "gzip";

    /**
     * Trigger the verbose mode detailing all operations
     */
    private boolean verbose;

    private Collection<DataProducer> dataProducers = new ArrayList<DataProducer>();


    public void setDestfile( File deb ) {
        this.deb = deb;
    }

    public void setControl( File control ) {
        this.control = control;
    }

    public void setChangesIn( File changes ) {
        this.changesIn = changes;
    }

    public void setChangesOut( File changes ) {
        this.changesOut = changes;
    }

    public void setChangesSave( File changes ) {
        this.changesSave = changes;
    }

    public void setKeyring( File keyring ) {
        this.keyring = keyring;
    }

    public void setKey( String key ) {
        this.key = key;
    }

    public void setPassphrase( String passphrase ) {
        this.passphrase = passphrase;
    }

    public void setCompression( String compression ) {
        this.compression = compression;
    }

    public void setVerbose( boolean verbose ) {
        this.verbose = verbose;
    }

    public void addFileSet( FileSet fileset ) {
        dataProducers.add(new DataProducerFileSet(fileset));
    }

    public void addTarFileSet( Tar.TarFileSet fileset ) {
        dataProducers.add(new DataProducerFileSet(fileset));
    }

    public void addData( Data data ) {
        dataProducers.add(data);
    }

    private boolean isPossibleOutput( File file ) {

        if (file == null) {
            return false;
        }

        if (file.exists()) {
            return file.isFile() && file.canWrite();
        }

        return true;
    }

    public void execute() {

        if (control == null || !control.isDirectory()) {
            throw new BuildException("You need to point the 'control' attribute to the control directory.");
        }

        if (changesIn != null) {

            if (!changesIn.isFile() || !changesIn.canRead()) {
                throw new BuildException("The 'changesIn' attribute needs to point to a readable file. " + changesIn + " was not found/readable.");
            }

            if (changesOut == null) {
                throw new BuildException("A 'changesIn' without a 'changesOut' does not make much sense.");
            }

            if (!isPossibleOutput(changesOut)) {
                throw new BuildException("Cannot write the output for 'changesOut' to " + changesOut);
            }

            if (changesSave != null && !isPossibleOutput(changesSave)) {
                throw new BuildException("Cannot write the output for 'changesSave' to " + changesSave);
            }

        } else {
            if (changesOut != null || changesSave != null) {
                throw new BuildException("The 'changesOut' or 'changesSave' attributes may only be used when there is a 'changesIn' specified.");
            }
        }

        if (Compression.toEnum(compression) == null) {
            throw new BuildException("The compression method '" + compression + "' is not supported (expected 'none', 'gzip' or 'bzip2')");
        }

        if (dataProducers.size() == 0) {
            throw new BuildException("You need to provide at least one reference to a tgz or directory with data.");
        }

        // validation of the type of the <data> elements
        for (DataProducer dataProducer : dataProducers) {
            if (dataProducer instanceof Data) {
                Data data = (Data) dataProducer;
                if (data.getType() == null) {
                    throw new BuildException("The type of the data element wasn't specified (expected 'file', 'directory' or 'archive')");
                } else if (!Arrays.asList("file", "directory", "archive").contains(data.getType().toLowerCase())) {
                    throw new BuildException("The type '" + data.getType() + "' of the data element is unknown (expected 'file', 'directory' or 'archive')");
                }
            }
        }
        
        if (deb == null) {
            throw new BuildException("You need to point the 'destfile' attribute to where the deb is supposed to be created.");
        }

        final File[] controlFiles = control.listFiles();

        final DataProducer[] data = new DataProducer[dataProducers.size()];
        dataProducers.toArray(data);

        final Processor processor = new Processor(new TaskConsole(this, verbose), null);

        final BinaryPackageControlFile packageControlFile;
        try {

            log("Creating debian package: " + deb);

            packageControlFile = processor.createDeb(controlFiles, data, deb, Compression.toEnum(compression));

        } catch (Exception e) {
            // what the fuck ant? why are you not printing the exception chain?
            e.printStackTrace();
            throw new BuildException("Failed to create debian package " + deb, e);
        }
        
        makeChangesFiles(processor, packageControlFile);
    }

    private void makeChangesFiles(Processor processor, BinaryPackageControlFile packageControlFile) throws BuildException {
        if (changesOut == null) {
            return;
        }
        
        TextfileChangesProvider changesProvider;
        FileOutputStream out = null;
        
        try {
            log("Creating changes file: " + changesOut);
            
            out = new FileOutputStream(changesOut);
            
            // for now only support reading the changes form a textfile provider
            changesProvider = new TextfileChangesProvider(new FileInputStream(changesIn), packageControlFile);
            
            ChangesFile changesFile = processor.createChanges(packageControlFile, changesProvider);
            
            if (keyring != null && key != null && passphrase != null) {
                log("Signing the changes file with the key " + key);
                PGPSigner signer = new PGPSigner(new FileInputStream(keyring), key, passphrase);
                signer.clearSign(changesFile.toString(), out);
            } else {
                out.write(changesFile.toString().getBytes("UTF-8"));
            }
            out.flush();

        } catch (Exception e) {
            throw new BuildException("Failed to create the Debian changes file " + changesOut, e);
        } finally {
            IOUtils.closeQuietly(out);
        }
        
        if (changesSave == null) {
            return;
        }
        
        try {
            log("Saving changes to file: " + changesSave);

            changesProvider.save(new FileOutputStream(changesSave));

        } catch (Exception e) {
            throw new BuildException("Failed to save debian changes file " + changesSave, e);
        }
    }
}
