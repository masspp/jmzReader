package uk.ac.ebi.pride.tools.mgf_parser;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import uk.ac.ebi.pride.tools.jmzreader.JMzIterableReader;
import uk.ac.ebi.pride.tools.jmzreader.JMzReaderException;
import uk.ac.ebi.pride.tools.jmzreader.model.Spectrum;
import uk.ac.ebi.pride.tools.mgf_parser.model.Ms2Query;
import uk.ac.ebi.pride.tools.mgf_parser.model.PmfQuery;
import uk.ac.ebi.pride.tools.utils.StringUtils;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.util.regex.Matcher;
import java.util.stream.Stream;

/**
 * This implementation only allows to iterate over all the spectra in a file and retrieve the corresponding
 * spectra. This implementation is faster that the MgfFile for iterable read of files but can't be used for RandomAccess
 *
 * @author ypriverol
 */
public class MgfIterableFile implements JMzIterableReader {

    public static final Logger logger = LoggerFactory.getLogger(MgfIterableFile.class);

    private boolean allowCustomTags = MgfUtils.DEFAULT_ALLOW_CUSTOM_TAGS;

    /**
     * If this option is set, comments are not removed
     * from MGF files. This speeds up parsing considerably
     * but causes problems if MGF files do contain comments.
     */
    private boolean disableCommentSupport = false;

    /**
     * This function helps to ignore peaks if the parser found parser errors in the peaks
     */
    private boolean ignoreWrongPeaks = MgfUtils.DEFAULT_IGNORE_WRONG_PEAKS;

    /**
     * Source File containing all the spectra.
     */
    private File sourceFile;

    private final FileChannel accessChannel;
    private MappedByteBuffer buffer;

    public MgfIterableFile(File file, boolean ignoreWrongPeaks, boolean disableCommentSupport, boolean allowCustomTags) throws JMzReaderException {

        this.ignoreWrongPeaks = ignoreWrongPeaks;
        this.disableCommentSupport = disableCommentSupport;
        this.allowCustomTags = allowCustomTags;
        this.sourceFile = file;

        try {
            RandomAccessFile accessFile = new RandomAccessFile(file, "rw");
            accessChannel = accessFile.getChannel();
        } catch (IOException e) {
           throw new JMzReaderException("Error reading the following file " + file.getAbsolutePath(), e);
        }

    }

    @Override
    public boolean hasNext() {
        if(buffer == null || !buffer.hasRemaining())
            readBuffer();
        int previousPosition = buffer.position();
        buffer.get();
        int nextPosition = buffer.position();
        buffer.position(previousPosition);
        return buffer.hasRemaining() && nextPosition != previousPosition;
    }

    @Override
    public Spectrum next() throws JMzReaderException {
        Ms2Query spectrum = null;
        if(buffer == null || !buffer.hasRemaining()){
            readBuffer();
        }
        boolean inAttributeSection = true;
        StringBuffer stringBuffer = new StringBuffer();
        for (int i = 0; i < buffer.limit(); i++) {
            char ch = ((char) buffer.get());
            if(ch=='\n'){
                stringBuffer.append(ch);
                String line = StringUtils.removeBOMString(stringBuffer.toString().trim());

                if(line.contains("BEGIN IONS")) {
                    logger.debug("Start reading the following spectrum -- ");
                    spectrum = new Ms2Query(this.disableCommentSupport);
                }else if(line.contains("END IONS"))
                    return spectrum;
                else if(spectrum != null){
                    /**
                     * Some files can have a lot of empty and nonsense information between Spectrums
                     */
                    if (!disableCommentSupport)
                        line = line.replaceAll(MgfFile.mgfCommentRegex, line);
                    if (line.length() < 1) { // ignore empty lines
                        continue;
                    }

                    Matcher attributeMatcher = MgfFile.attributePattern.matcher(line); // check if it's a property
                    boolean matchesAttributePattern = false;
                    if (inAttributeSection) {
                        matchesAttributePattern = attributeMatcher.find();
                    }
                    if (matchesAttributePattern) {
                        if (attributeMatcher.groupCount() != 2) {
                            throw new JMzReaderException("Invalid attribute line encountered in MS2 query: " + line);
                        }
                        String name = attributeMatcher.group(1);
                        String value = attributeMatcher.group(2);
                        spectrum.saveAttribute(name, value);
                    } else {
                        String cleanedLine = line.replaceAll("\\s+", " ");
                        int indexSpace = cleanedLine.indexOf(' ');
                        if (indexSpace >= 0) {
                            String firstHalf = cleanedLine.substring(0, indexSpace);
                            String secondHalf = cleanedLine.substring(indexSpace + 1);
                            int anotherSpace = secondHalf.indexOf(' ');
                            Double intensity;
                            if (anotherSpace < 0) {
                                intensity = Double.parseDouble(secondHalf);
                            } else { // ignore extra fragment charge number (3rd field), may be present
                                intensity = StringUtils.smartParseDouble((secondHalf.substring(0, anotherSpace)));
                            }
                            spectrum.addPeak(Double.parseDouble(firstHalf), intensity);
                        } else {  // no index could be found
                            if (ignoreWrongPeaks) {
                                logger.error("The following peaks and wronly annotated -- " + line);
                            } else
                                throw new JMzReaderException("Unable to parse 'mz' and 'intensity' values for " + line);
                        }
                        inAttributeSection = false;
                    }

                }

                stringBuffer = new StringBuffer();

            }else{
                stringBuffer.append(ch);
            }
        }
        return spectrum;
    }

    private void readBuffer(){
        try {
            buffer = accessChannel.map(FileChannel.MapMode.READ_ONLY, 0, MgfUtils.BUFFER_SIZE_100MB);
            buffer.load();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    @Override
    public Stream<Spectrum> next(int batch) {
        return null;
    }

    @Override
    public void close() throws JMzReaderException {
        try {
            accessChannel.close();
        } catch (IOException e) {
            throw new JMzReaderException("The following file can't be close -- " + sourceFile, e);
        }

    }
}
