/*
 * Copyright 2015 OpenCB
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.opencb.cellbase.app.cli;

import com.beust.jcommander.ParameterException;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import htsjdk.tribble.readers.LineIterator;
import htsjdk.variant.variantcontext.VariantContext;
import htsjdk.variant.vcf.VCFFileReader;
import htsjdk.variant.vcf.VCFHeader;
import htsjdk.variant.vcf.VCFHeaderVersion;
import org.apache.commons.lang.math.NumberUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.bson.Document;
import org.opencb.biodata.formats.variant.annotation.io.JsonAnnotationWriter;
import org.opencb.biodata.formats.variant.annotation.io.VariantAvroDataWriter;
import org.opencb.biodata.formats.variant.annotation.io.VepFormatReader;
import org.opencb.biodata.formats.variant.annotation.io.VepFormatWriter;
import org.opencb.biodata.formats.variant.io.JsonVariantReader;
import org.opencb.biodata.formats.variant.vcf4.FullVcfCodec;
import org.opencb.biodata.models.variant.Variant;
import org.opencb.biodata.models.variant.avro.VariantAnnotation;
import org.opencb.biodata.models.variant.avro.VariantAvro;
import org.opencb.biodata.models.variant.exceptions.NonStandardCompliantSampleField;
import org.opencb.biodata.tools.sequence.FastaIndexManager;
import org.opencb.biodata.tools.variant.VariantNormalizer;
import org.opencb.biodata.tools.variant.converters.avro.VariantContextToVariantConverter;
import org.opencb.cellbase.app.cli.variant.annotation.*;
import org.opencb.cellbase.client.config.ClientConfiguration;
import org.opencb.cellbase.client.rest.CellBaseClient;
import org.opencb.cellbase.core.api.DBAdaptorFactory;
import org.opencb.cellbase.core.api.GenomeDBAdaptor;
import org.opencb.cellbase.core.variant.annotation.VariantAnnotationCalculator;
import org.opencb.cellbase.core.variant.annotation.VariantAnnotationUtils;
import org.opencb.cellbase.core.variant.annotation.VariantAnnotator;
import org.opencb.cellbase.core.variant.annotation.CellBaseNormalizerSequenceAdaptor;
import org.opencb.cellbase.lib.impl.MongoDBAdaptorFactory;
import org.opencb.commons.ProgressLogger;
import org.opencb.commons.datastore.core.Query;
import org.opencb.commons.datastore.core.QueryOptions;
import org.opencb.commons.datastore.core.QueryResult;
import org.opencb.commons.io.DataReader;
import org.opencb.commons.io.DataWriter;
import org.opencb.commons.io.StringDataReader;
import org.opencb.commons.run.ParallelTaskRunner;
import org.opencb.commons.utils.FileUtils;
import org.rocksdb.Options;
import org.rocksdb.RocksDB;
import org.rocksdb.RocksDBException;
import org.rocksdb.RocksIterator;

import java.io.*;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.zip.GZIPInputStream;

import static java.nio.file.StandardOpenOption.APPEND;

/**
 * Created by fjlopez on 18/03/15.
 */
public class VariantAnnotationCommandExecutor extends CommandExecutor {

    public enum FileFormat {VCF, JSON, AVRO, VEP};

    private CliOptionsParser.VariantAnnotationCommandOptions variantAnnotationCommandOptions;

    private Path input;
    private Path output;
    private String url;
    private boolean local;
    private boolean cellBaseAnnotation;
    private boolean benchmark;
    private Path referenceFasta;
    private boolean normalize;
    private boolean decompose;
    private boolean leftAlign;
    private List<String> chromosomeList;
    private int port;
    private String species;
    private String assembly;
    private int numThreads;
    private int batchSize;
    private List<Path> customFiles;
    private Path populationFrequenciesFile = null;
    private Boolean completeInputPopulation;
    private List<RocksDB> dbIndexes;
    private List<Options> dbOptions;
    private List<String> dbLocations;
    private List<String> customFileIds;
    private List<List<String>> customFileFields;
    private int maxOpenFiles = -1;
    private FileFormat inputFormat;
    private FileFormat outputFormat;

    private QueryOptions queryOptions;

    private DBAdaptorFactory dbAdaptorFactory = null;

    private final int QUEUE_CAPACITY = 10;
    private final String TMP_DIR = "/tmp/";
    private static final String VARIATION_ANNOTATION_FILE_PREFIX = "variation_annotation_";

    public VariantAnnotationCommandExecutor(CliOptionsParser.VariantAnnotationCommandOptions variantAnnotationCommandOptions) {
        super(variantAnnotationCommandOptions.commonOptions.logLevel, variantAnnotationCommandOptions.commonOptions.verbose,
                variantAnnotationCommandOptions.commonOptions.conf);

        this.variantAnnotationCommandOptions = variantAnnotationCommandOptions;
        this.queryOptions = new QueryOptions();

        if (variantAnnotationCommandOptions.input != null) {
            input = Paths.get(variantAnnotationCommandOptions.input);
        }

        if (variantAnnotationCommandOptions.output != null) {
            output = Paths.get(variantAnnotationCommandOptions.output);
        }
    }

    @Override
    public void execute() {

        try {
            checkParameters();
            if (benchmark) {
                runBenchmark();
            } else {
                runAnnotation();
            }
            logger.info("Finished");
        } catch (Exception e) {
            e.printStackTrace();
            System.exit(1);
        }
    }

    private void runBenchmark() {
        try {

            FastaIndexManager fastaIndexManager = getFastaIndexManger();
            DirectoryStream<Path> stream = Files.newDirectoryStream(input, entry -> {
                return entry.getFileName().toString().endsWith(".vep");
            });

            DataWriter<Pair<VariantAnnotationDiff, VariantAnnotationDiff>> dataWriter = new BenchmarkDataWriter("VEP", "CellBase", output);
            ParallelTaskRunner.Config config = new ParallelTaskRunner.Config(numThreads, batchSize, QUEUE_CAPACITY, false);
            List<ParallelTaskRunner.TaskWithException<VariantAnnotation, Pair<VariantAnnotationDiff, VariantAnnotationDiff>, Exception>>
                    variantAnnotatorTaskList = getBenchmarkTaskList(fastaIndexManager);
            for (Path entry : stream) {
                logger.info("Processing file '{}'", entry.toString());
                DataReader dataReader = new VepFormatReader(input.resolve(entry.getFileName()).toString());
                ParallelTaskRunner<VariantAnnotation, Pair<VariantAnnotationDiff, VariantAnnotationDiff>> runner
                        = new ParallelTaskRunner<>(dataReader, variantAnnotatorTaskList, dataWriter, config);
                runner.run();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private FastaIndexManager getFastaIndexManger() {
        // Preparing the fasta file for fast accessing
        FastaIndexManager fastaIndexManager = null;
        try {
            fastaIndexManager = new FastaIndexManager(referenceFasta, true);
            if (!fastaIndexManager.isConnected()) {
                fastaIndexManager.index();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        return fastaIndexManager;
    }

    private List<ParallelTaskRunner.TaskWithException<VariantAnnotation, Pair<VariantAnnotationDiff, VariantAnnotationDiff>, Exception>>
    getBenchmarkTaskList(FastaIndexManager fastaIndexManager) throws IOException {
        List<ParallelTaskRunner.TaskWithException<VariantAnnotation, Pair<VariantAnnotationDiff, VariantAnnotationDiff>, Exception>>
                benchmarkTaskList = new ArrayList<>(numThreads);
        for (int i = 0; i < numThreads; i++) {
            // Benchmark variants are read from a VEP file, must not normalize
            benchmarkTaskList.add(new BenchmarkTask(createCellBaseAnnotator(), fastaIndexManager));
        }
        return benchmarkTaskList;
    }

    private boolean runAnnotation() throws Exception {

        // Build indexes for custom files and/or population frequencies file
        getIndexes();
        try {
            if (variantAnnotationCommandOptions.variant != null && !variantAnnotationCommandOptions.variant.isEmpty()) {
                List<Variant> variants = Variant.parseVariants(variantAnnotationCommandOptions.variant);
                if (local) {
                    DBAdaptorFactory dbAdaptorFactory = new MongoDBAdaptorFactory(configuration);
                    VariantAnnotationCalculator variantAnnotationCalculator =
                            new VariantAnnotationCalculator(this.species, this.assembly, dbAdaptorFactory);
                    List<QueryResult<VariantAnnotation>> annotationByVariantList =
                            variantAnnotationCalculator.getAnnotationByVariantList(variants, queryOptions);

                    ObjectMapper jsonObjectMapper = new ObjectMapper();
                    jsonObjectMapper.setSerializationInclusion(JsonInclude.Include.NON_NULL);
                    jsonObjectMapper.configure(MapperFeature.REQUIRE_SETTERS_FOR_GETTERS, true);
                    ObjectWriter objectWriter = jsonObjectMapper.writer();

                    Path outPath = Paths.get(variantAnnotationCommandOptions.output);
                    FileUtils.checkDirectory(outPath.getParent());
                    BufferedWriter bufferedWriter = FileUtils.newBufferedWriter(outPath);
                    for (QueryResult queryResult : annotationByVariantList) {
                        bufferedWriter.write(objectWriter.writeValueAsString(queryResult.getResult()));
                        bufferedWriter.newLine();
                    }
                    bufferedWriter.close();
                }
                return true;
            }

            // If a variant file is provided then we annotate it. Lines in the input file can be computationally
            // expensive to parse, i.e.: multisample vcf with thousands of samples. A specific task is created to enable
            // parallel parsing of these lines
            if (input != null) {
                DataReader<String> dataReader = new StringDataReader(input);
                List<ParallelTaskRunner.TaskWithException<String, Variant, Exception>> variantAnnotatorTaskList
                        = getStringTaskList();
                DataWriter<Variant> dataWriter = getVariantDataWriter(output.toString());

                ParallelTaskRunner.Config config = new ParallelTaskRunner.Config(numThreads, batchSize, QUEUE_CAPACITY, false);
                ParallelTaskRunner<String, Variant> runner =
                        new ParallelTaskRunner<>(dataReader, variantAnnotatorTaskList, dataWriter, config);
                runner.run();
                // For internal use only - will only be run when -Dpopulation-frequencies is activated
                writeRemainingPopFrequencies();
            } else {
                // This will annotate the CellBase Variation collection
                if (cellBaseAnnotation) {
                    // TODO: enable this query in the parseQuery method within VariantMongoDBAdaptor
//                    Query query = new Query("$match",
//                            new Document("annotation.consequenceTypes", new Document("$exists", 0)));
//                    Query query = new Query();
                    QueryOptions options = new QueryOptions("include", "chromosome,start,reference,alternate,type");
                    List<ParallelTaskRunner.TaskWithException<Variant, Variant, Exception>> variantAnnotatorTaskList
                            = getVariantTaskList();
                    ParallelTaskRunner.Config config = new ParallelTaskRunner.Config(numThreads, batchSize, QUEUE_CAPACITY, false);

                    for (String chromosome : chromosomeList) {
                        logger.info("Annotating chromosome {}", chromosome);
                        Query query = new Query("chromosome", chromosome);
                        DataReader<Variant> dataReader =
                                new VariationDataReader(dbAdaptorFactory.getVariationDBAdaptor(species), query, options);
                        DataWriter<Variant> dataWriter = getVariantDataWriter(output.toString() + "/"
                                + VARIATION_ANNOTATION_FILE_PREFIX + chromosome + ".json.gz");
                        ParallelTaskRunner<Variant, Variant> runner =
                                new ParallelTaskRunner<Variant, Variant>(dataReader, variantAnnotatorTaskList, dataWriter, config);
                        runner.run();
                    }
                }
            }
        } finally {
            if (customFiles != null || populationFrequenciesFile != null) {
                closeIndexes();
            }
            if (dbAdaptorFactory != null) {
                dbAdaptorFactory.close();
            }
        }

        logger.info("Variant annotation finished.");
        return false;
    }

    private void writeRemainingPopFrequencies() throws IOException {
        // For internal use only - will only be run when -Dpopulation-frequencies is activated
        if (populationFrequenciesFile != null) {
            if (completeInputPopulation) {
                DataWriter dataWriter = new JsonAnnotationWriter(output.toString(), APPEND);
                dataWriter.open();
                dataWriter.pre();

                // Population frequencies rocks db will always be the last one in the list. DO NOT change the name of the
                // rocksIterator variable - for some unexplainable reason Java VM crashes if it's named "iterator"
                RocksIterator rocksIterator = dbIndexes.get(dbIndexes.size() - 1).newIterator();

                ObjectMapper mapper = new ObjectMapper();
                logger.info("Writing variants with frequencies that were not found within the input file to {}",
                        populationFrequenciesFile.toString(), output.toString());
                int counter = 0;
                for (rocksIterator.seekToFirst(); rocksIterator.isValid(); rocksIterator.next()) {
                    VariantAvro variantAvro = mapper.readValue(rocksIterator.value(), VariantAvro.class);
                    // The additional attributes field initialized with an empty map is used as the flag to indicate that
                    // this variant was not visited during the annotation process
                    if (variantAvro.getAnnotation().getAdditionalAttributes() == null) {
                        dataWriter.write(new Variant(variantAvro));
                    }

                    counter++;
                    if (counter % 10000 == 0) {
                        logger.info("{} written", counter);
                    }
                }
                dataWriter.post();
                dataWriter.close();
                logger.info("Done.");
            } else {
                logger.warn("complete-input-population set to false, variants in population frequencies file {} not in "
                        + "input file {} will not be appended to output file.", populationFrequenciesFile, input);
            }
        }
    }

    private void setChromosomeList() {

        if (variantAnnotationCommandOptions.chromosomeList != null
                && !variantAnnotationCommandOptions.chromosomeList.isEmpty()) {
            chromosomeList = Arrays.asList(variantAnnotationCommandOptions.chromosomeList.split(","));
            logger.info("Setting chromosomes {} for variant annotation", chromosomeList.toString());
        // If the user does not provide any chromosome, fill chromosomeList with all available chromosomes in the
        // database
        } else {
            logger.info("Getting full list of chromosome names in the database");
            dbAdaptorFactory = new MongoDBAdaptorFactory(configuration);
            GenomeDBAdaptor genomeDBAdaptor = dbAdaptorFactory.getGenomeDBAdaptor(species, assembly);
            QueryResult queryResult = genomeDBAdaptor.getGenomeInfo(new QueryOptions("include", "chromosomes.name"));

            List<Document> chromosomeDocumentList = (List<Document>) ((List<Document>) queryResult.getResult()).get(0).get("chromosomes");
            chromosomeList = new ArrayList<>(chromosomeDocumentList.size());
            for (Document chromosomeDocument : chromosomeDocumentList) {
                chromosomeList.add((String) chromosomeDocument.get("name"));
            }
            logger.info("Available chromosomes: {}", chromosomeList.toString());
        }
    }

    private DataWriter<Variant> getVariantDataWriter(String filename) {
        DataWriter<Variant> dataWriter = null;
        if (outputFormat.equals(FileFormat.JSON)) {
            dataWriter = new JsonAnnotationWriter(filename);
        } else if (outputFormat.equals(FileFormat.AVRO)) {
            ProgressLogger progressLogger = new ProgressLogger("Num written variants:");
            dataWriter = new VariantAvroDataWriter(Paths.get(filename), true)
                    .setProgressLogger(progressLogger);
        } else if (outputFormat.equals(FileFormat.VEP)) {
            dataWriter = new VepFormatWriter(filename);
        }

        return dataWriter;
    }

    private List<ParallelTaskRunner.TaskWithException<String, Variant, Exception>> getStringTaskList() throws IOException {
        List<ParallelTaskRunner.TaskWithException<String, Variant, Exception>> variantAnnotatorTaskList = new ArrayList<>(numThreads);
        VcfStringAnnotatorTask.SharedContext sharedContext = new VcfStringAnnotatorTask.SharedContext(numThreads);
//        Set<String> breakendMates = Collections.synchronizedSet(new HashSet<>());
        for (int i = 0; i < numThreads; i++) {
            List<VariantAnnotator> variantAnnotatorList = createAnnotators();
            switch (inputFormat) {
                case VCF:
                    logger.info("Using HTSJDK to read variants.");
                    FullVcfCodec codec = new FullVcfCodec();
                    try (InputStream fileInputStream = input.toString().endsWith("gz")
                            ? new GZIPInputStream(new FileInputStream(input.toFile()))
                            : new FileInputStream(input.toFile())) {
                        LineIterator lineIterator = codec.makeSourceFromStream(fileInputStream);
                        VCFHeader header = (VCFHeader) codec.readActualHeader(lineIterator);
                        VCFHeaderVersion headerVersion = codec.getVCFHeaderVersion();
                        variantAnnotatorTaskList.add(new VcfStringAnnotatorTask(header, headerVersion,
                                variantAnnotatorList, sharedContext, normalize, getNormalizerConfig()));
                    } catch (IOException e) {
                        throw new IOException("Unable to read VCFHeader");
                    }
                    break;
                case JSON:
                    logger.info("Using a JSON parser to read variants...");
                    variantAnnotatorTaskList.add(new JsonStringAnnotatorTask(variantAnnotatorList, normalize,
                            getNormalizerConfig()));
                    break;
                default:
                    break;
            }
        }
        return variantAnnotatorTaskList;
    }

    private VariantNormalizer.VariantNormalizerConfig getNormalizerConfig() throws IOException {
        VariantNormalizer.VariantNormalizerConfig variantNormalizerConfig = (new VariantNormalizer.VariantNormalizerConfig())
                .setReuseVariants(true)
                .setNormalizeAlleles(false)
                .setDecomposeMNVs(decompose);

        // Enable left align
        if (leftAlign) {
            // WARN: If --reference-fasta is present will override CellBase reference genome even if --local was present
            if (referenceFasta != null) {
                return variantNormalizerConfig.enableLeftAlign(referenceFasta.toString());
            } else {
                // dbAdaptorFactory may have been already initialized while creating CellBase annotators or at execute if
                // annotating CellBase variation collection
                if (dbAdaptorFactory == null) {
                    dbAdaptorFactory = new MongoDBAdaptorFactory(configuration);
                }
                return variantNormalizerConfig
                        .enableLeftAlign(new CellBaseNormalizerSequenceAdaptor(dbAdaptorFactory
                                .getGenomeDBAdaptor(species, assembly)));
            }
        }
        return variantNormalizerConfig;
    }

    private List<ParallelTaskRunner.TaskWithException<Variant, Variant, Exception>> getVariantTaskList()
            throws IOException {
        List<ParallelTaskRunner.TaskWithException<Variant, Variant, Exception>> variantAnnotatorTaskList
                = new ArrayList<>(numThreads);
        for (int i = 0; i < numThreads; i++) {
            List<VariantAnnotator> variantAnnotatorList = createAnnotators();
            variantAnnotatorTaskList.add(new VariantAnnotatorTask(variantAnnotatorList));
        }

        return variantAnnotatorTaskList;
    }

    private void closeIndexes() throws IOException {
        for (int i = 0; i < dbIndexes.size(); i++) {
            dbIndexes.get(i).close();
            dbOptions.get(i).dispose();
        }

        if (populationFrequenciesFile != null) {
            org.apache.commons.io.FileUtils.deleteDirectory(new File(dbLocations.get(dbLocations.size() - 1)));
        }
    }

    private List<VariantAnnotator> createAnnotators() {
        List<VariantAnnotator> variantAnnotatorList;
        variantAnnotatorList = new ArrayList<>();

        // CellBase annotator is always called
        variantAnnotatorList.add(createCellBaseAnnotator());

        // Include custom annotators if required
        if (customFiles != null) {
            for (int i = 0; i < customFiles.size(); i++) {
                if (customFiles.get(i).toString().endsWith(".vcf") || customFiles.get(i).toString().endsWith(".vcf.gz")) {
                    variantAnnotatorList.add(new VcfVariantAnnotator(customFiles.get(i).toString(), dbIndexes.get(i),
                            customFileIds.get(i), customFileFields.get(i)));
                }
            }
        }

        // Include population-frequencies file if required
        if (populationFrequenciesFile != null) {
            // Rocks db connection is always the last in the list
            int i = dbIndexes.size() - 1;
            variantAnnotatorList.add(new PopulationFrequenciesAnnotator(populationFrequenciesFile.toString(),
                    dbIndexes.get(i)));

        }

        return variantAnnotatorList;
    }

    private VariantAnnotator createCellBaseAnnotator() {
        // Assume annotation of CellBase variation collection will always be carried out from a local installation
        if (local || cellBaseAnnotation) {
            // dbAdaptorFactory may have been already initialized at execute if annotating CellBase variation collection
            if (dbAdaptorFactory == null) {
                dbAdaptorFactory = new MongoDBAdaptorFactory(configuration);
            }
            // Normalization should just be performed in one place: before calling the annotation calculator - within the
            // corresponding *AnnotatorTask since the AnnotatorTasks need that the number of sent variants coincides
            // equals the number of returned annotations
            return new CellBaseLocalVariantAnnotator(new VariantAnnotationCalculator(species, assembly,
                    dbAdaptorFactory), queryOptions);
        } else {
            try {
                ClientConfiguration clientConfiguration = ClientConfiguration.load(getClass()
                        .getResourceAsStream("/client-configuration.yml"));
                if (url != null) {
                    clientConfiguration.getRest().setHosts(Collections.singletonList(url));
                }
                clientConfiguration.setDefaultSpecies(species);
                CellBaseClient cellBaseClient;
                cellBaseClient = new CellBaseClient(clientConfiguration);
                logger.debug("URL set to: {}", url);

                // TODO: normalization must be carried out in the client - phase set must be sent together with the
                // TODO: variant string to the server for proper phase annotation by REST
                return new CellBaseWSVariantAnnotator(cellBaseClient.getVariantClient(), queryOptions);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        return null;

    }

    private void getIndexes() {
        dbIndexes = new ArrayList<>();
        dbOptions = new ArrayList<>();
        dbLocations = new ArrayList<>();

        // Index custom files if provided
        if (customFiles != null) {
            for (int i = 0; i < customFiles.size(); i++) {
                if (customFiles.get(i).toString().endsWith(".vcf") || customFiles.get(i).toString().endsWith(".vcf.gz")) {
                    Object[] dbConnection = getDBConnection(customFiles.get(i).toString() + ".idx");
                    RocksDB rocksDB = (RocksDB) dbConnection[0];
                    Options dbOption = (Options) dbConnection[1];
                    String dbLocation = (String) dbConnection[2];
                    boolean indexingNeeded = (boolean) dbConnection[3];
                    if (indexingNeeded) {
                        logger.info("Creating index DB at {} ", dbLocation);
                        indexCustomVcfFile(i, rocksDB);
                    } else {
                        logger.info("Index found at {}", dbLocation);
                        logger.info("Skipping index creation");
                    }
                    dbIndexes.add(rocksDB);
                    dbOptions.add(dbOption);
                    dbLocations.add(dbLocation);
                }
            }
        }

        // Index population frequencies file if provided
        if (populationFrequenciesFile != null) {
            // We force the creation of a new index even if there was one already - Annotation of frequencies from
            // these files implies deletions on the RocksDB database. Whatever is already there will probably be wrong
            Object[] dbConnection = getDBConnection(populationFrequenciesFile + ".idx", true);
            RocksDB rocksDB = (RocksDB) dbConnection[0];
            Options dbOption = (Options) dbConnection[1];
            String dbLocation = (String) dbConnection[2];

            logger.info("Creating index DB at {} ", dbLocation);
            indexPopulationFrequencies(rocksDB);

            dbIndexes.add(rocksDB);
            dbOptions.add(dbOption);
            dbLocations.add(dbLocation);
        }
    }

    private void indexPopulationFrequencies(RocksDB db) {
        ObjectMapper jsonObjectMapper = new ObjectMapper();
        jsonObjectMapper.configure(MapperFeature.REQUIRE_SETTERS_FOR_GETTERS, true);
        ObjectWriter jsonObjectWriter = jsonObjectMapper.writer();

        try {
            DataReader<Variant> dataReader = new JsonVariantReader(populationFrequenciesFile.toString());
            dataReader.open();
            dataReader.pre();
            int lineCounter = 0;
            List<Variant> variant = dataReader.read();
            while (variant != null) {
                db.put(VariantAnnotationUtils.buildVariantId(variant.get(0).getChromosome(), variant.get(0).getStart(),
                        variant.get(0).getReference(), variant.get(0).getAlternate()).getBytes(),
                        jsonObjectWriter.writeValueAsBytes(variant.get(0).getImpl()));
                lineCounter++;
                if (lineCounter % 100000 == 0) {
                    logger.info("{} lines indexed", lineCounter);
                }
                variant = dataReader.read();
            }
            dataReader.post();
            dataReader.close();
        } catch (IOException | RocksDBException e) {
            e.printStackTrace();
        }
    }

    private Object[] getDBConnection(String dbLocation) {
        return getDBConnection(dbLocation, false);
    }

    private Object[] getDBConnection(String dbLocation, boolean forceCreate) {
        boolean indexingNeeded = forceCreate || !Files.exists(Paths.get(dbLocation));
        // a static method that loads the RocksDB C++ library.
        RocksDB.loadLibrary();
        // the Options class contains a set of configurable DB options
        // that determines the behavior of a database.
        Options options = new Options().setCreateIfMissing(true);
        if (maxOpenFiles > 0) {
            options.setMaxOpenFiles(maxOpenFiles);
        }

        RocksDB db = null;
        try {
            // a factory method that returns a RocksDB instance
            if (indexingNeeded) {
                db = RocksDB.open(options, dbLocation);
            } else {
                db = RocksDB.openReadOnly(options, dbLocation);
            }
            // do something
        } catch (RocksDBException e) {
            // do some error handling
            e.printStackTrace();
            System.exit(1);
        }

        return new Object[]{db, options, dbLocation, indexingNeeded};

    }

    private void indexCustomVcfFile(int customFileNumber, RocksDB db) {
        ObjectMapper jsonObjectMapper = new ObjectMapper();
        ObjectWriter jsonObjectWriter = jsonObjectMapper.writer();
        int lineCounter = -1;
        VariantContext variantContext = null;
        try {
            VCFFileReader vcfFileReader = new VCFFileReader(customFiles.get(customFileNumber).toFile(), false);
            Iterator<VariantContext> iterator = vcfFileReader.iterator();
            VariantContextToVariantConverter converter = new VariantContextToVariantConverter("", "",
                    vcfFileReader.getFileHeader().getSampleNamesInOrder());
            // Currently, only VCF files are supported for custom-annotation so makes no sense to allow no normalisation
            // of variants.
            // However, decomposition of MNVs/Block substitutions can still be optional
//            VariantNormalizer normalizer = new VariantNormalizer(true, false, decompose);
            VariantNormalizer normalizer = new VariantNormalizer(getNormalizerConfig());
            lineCounter = 0;
            while (iterator.hasNext()) {
                variantContext = iterator.next();
                // Reference positions will not be indexed
                if (variantContext.getAlternateAlleles().size() > 0) {
                    // Currently, only VCF files are supported for custom-annotation so makes no sense to allow no normalisation
                    // of variants.
                    List<Variant> variantList = normalizer.normalize(converter.apply(Collections.singletonList(variantContext)), true);
                    for (Variant variant : variantList) {
                        db.put((variant.getChromosome() + "_" + variant.getStart() + "_" + variant.getReference() + "_"
                                        + variant.getAlternate()).getBytes(),
                                jsonObjectWriter.writeValueAsBytes(parseInfoAttributes(variant, customFileNumber)));
                    }
                }
                lineCounter++;
                if (lineCounter % 100000 == 0) {
                    logger.info("{} lines indexed", lineCounter);
                }
            }
            vcfFileReader.close();
        } catch (IOException | RocksDBException | NonStandardCompliantSampleField e) {
            e.printStackTrace();
            System.exit(1);
        } catch (Exception e) {
            if (lineCounter >= 0 && variantContext != null) {
                logger.error("Error fond while trying to parse {}:{}:{}:{}", variantContext.getContig(),
                        variantContext.getStart(), variantContext.getReference(), variantContext.getAlternateAlleles());
            } else {
                logger.error("Error found while parsing {}", customFiles.get(customFileNumber).toString());
            }
            throw e;
        }
    }

    protected Map<String, String> parseInfoAttributes(Variant variant, int customFileNumber) {
        Map<String, String> infoMap = variant.getStudies().get(0).getFiles().get(0).getAttributes();
        Map<String, String> parsedInfo = new HashMap<>();
        for (String attribute : infoMap.keySet()) {
            if (customFileFields.get(customFileNumber).contains(attribute)) {
                parsedInfo.put(attribute, infoMap.get(attribute));
//                parsedInfo.put(attribute, getValueFromString(infoMap.get(attribute)));
            }
        }

        return parsedInfo;
    }

    @Deprecated
    protected List<Map<String, Object>> parseInfoAttributes(String info, int numAlleles, int customFileNumber) {
        List<Map<String, Object>> infoAttributes = new ArrayList<>(numAlleles);
        for (int i = 0; i < numAlleles; i++) {
            infoAttributes.add(new HashMap<>());
        }
        for (String var : info.split(";")) {
            String[] splits = var.split("=");
            if (splits.length == 2 && customFileFields.get(customFileNumber).contains(splits[0])) {
                // Managing values for the allele
                String[] values = splits[1].split(",");
                // numAlleles and values.length may be different. For example, in the Exac vcf AN presents just one
                // value even if there are multiple alleles or, for example, for the AC_Het provide counts for all posible
                // heterozigous genotypes. In those cases, the hole string is pasted to all alleles
                if (values.length == numAlleles) {
                    for (int i = 0; i < numAlleles; i++) {
                        infoAttributes.get(i).put(splits[0], getValueFromString(values[i]));
                    }
                } else {
                    for (int i = 0; i < numAlleles; i++) {
                        infoAttributes.get(i).put(splits[0], getValueFromString(splits[1]));
                    }
                }
            }
        }

        return infoAttributes;
    }

    private Object getValueFromString(String value) {
        if (NumberUtils.isNumber(value)) {
            try {
                return Integer.parseInt(value);
            } catch (NumberFormatException e) {
                try {
                    return Float.parseFloat(value);
                } catch (NumberFormatException e1) {
                    return Double.parseDouble(value);
                }
            }
        } else {
            return value;
        }
    }

    private void checkParameters() throws IOException {

        // Get reference genome
        if (org.apache.commons.lang.StringUtils.isNotBlank(variantAnnotationCommandOptions.referenceFasta)) {
            referenceFasta = Paths.get(variantAnnotationCommandOptions.referenceFasta);
            FileUtils.checkFile(referenceFasta);
        }

        // Run benchmark
        benchmark = variantAnnotationCommandOptions.benchmark;
        if (benchmark) {
            if (referenceFasta == null) {
                throw new ParameterException("Reference genome must be provided for running the benchmark. Please, "
                        + "provide a valid path to a fasta file with the reference genome sequence by using the "
                        + "--reference-fasta parameter.");
            }
        }


        // Use cache
//        queryOptions.put("useCache", variantAnnotationCommandOptions.noCache ? "false" : "true");
        if (variantAnnotationCommandOptions.noCache) {
            logger.warn("********************************************************************************************");
            logger.warn("PLEASE NOTE that parameter --no-server-cache is no longer in use. It is deprecated and "
                    + "completely ignored by current implementation. Is just kept visible not to break scripts using "
                    + "it and will soon be removed from the interface. Please, have a look at the --server-cache "
                    + "parameter instead");
            logger.warn("********************************************************************************************");
        }

        queryOptions.put("useCache", variantAnnotationCommandOptions.cache);
        queryOptions.put("phased", variantAnnotationCommandOptions.phased);

        // input file
        if (variantAnnotationCommandOptions.input != null) {
            input = Paths.get(variantAnnotationCommandOptions.input);
            if (benchmark) {
                FileUtils.checkDirectory(input);
                normalize = false;
            } else {
                normalize =  !variantAnnotationCommandOptions.skipNormalize;
                FileUtils.checkFile(input);
                String fileName = input.toFile().getName();
                if (fileName.endsWith(".vcf") || fileName.endsWith(".vcf.gz")) {
                    inputFormat = FileFormat.VCF;
                } else if (fileName.endsWith(".json") || fileName.endsWith(".json.gz")) {
                    inputFormat = FileFormat.JSON;
                } else {
                    throw new ParameterException("Only VCF and JSON formats are currently accepted. Please provide a "
                            + "valid .vcf, .vcf.gz, json or .json.gz file");
                }
            }
        // Expected to read from variation collection - normalization must be avoided
        } else {
            normalize = false;
        }

        decompose = !variantAnnotationCommandOptions.skipDecompose;
        leftAlign = !variantAnnotationCommandOptions.skipLeftAlign;

        // output file
        if (variantAnnotationCommandOptions.output != null) {
            output = Paths.get(variantAnnotationCommandOptions.output);
            // output.getParent may be null if for example the output is specified with no path at all, i.e
            // -o test.vcf rather than -o ./test.vcf
            if (output.getParent() != null) {
                try {
                    FileUtils.checkDirectory(output.getParent());
                } catch (IOException e) {
                    throw new ParameterException(e);
                }
            }
//            if (!outputDir.toFile().exists()) {
//                throw new ParameterException("Output directory " + outputDir + " doesn't exist");
//            } else if (output.toFile().isDirectory()) {
//                throw new ParameterException("Output file cannot be a directory: " + output);
//            }
        } else {
            throw new ParameterException("Please check command line sintax. Provide a valid output file name.");
        }

        if (variantAnnotationCommandOptions.outputFormat != null) {
            switch (variantAnnotationCommandOptions.outputFormat.toLowerCase()) {
                case "json":
                    outputFormat = FileFormat.JSON;
                    break;
                case "avro":
                    outputFormat = FileFormat.AVRO;
                    break;
                case "vep":
                    outputFormat = FileFormat.VEP;
                    break;
                default:
                    throw  new ParameterException("Only JSON and VEP output formats are currently available. Please, select one of them.");
            }

        }

        if (variantAnnotationCommandOptions.include != null && !variantAnnotationCommandOptions.include.isEmpty()) {
            queryOptions.add("include", variantAnnotationCommandOptions.include);
        }

        if (variantAnnotationCommandOptions.exclude != null && !variantAnnotationCommandOptions.exclude.isEmpty()) {
            queryOptions.add("exclude", variantAnnotationCommandOptions.exclude);
        }

        // Num threads
        if (variantAnnotationCommandOptions.numThreads > 1) {
            numThreads = variantAnnotationCommandOptions.numThreads;
        } else {
            numThreads = 1;
            logger.warn("Incorrect number of numThreads, it must be a positive value. This has been reset to '{}'", numThreads);
        }

        // Batch size
        if (variantAnnotationCommandOptions.batchSize >= 1 && variantAnnotationCommandOptions.batchSize <= 2000) {
            batchSize = variantAnnotationCommandOptions.batchSize;
        } else {
            batchSize = 1;
            logger.warn("Incorrect size of batch size, it must be a positive value between 1-1000. This has been set to '{}'", batchSize);
        }

        // Direct connection to local MongoDB
        local = variantAnnotationCommandOptions.local;
        if (!variantAnnotationCommandOptions.local) {
            // Url
            if (variantAnnotationCommandOptions.url != null) {
                url = variantAnnotationCommandOptions.url;
            } else {
                throw new ParameterException("Please check command line sintax. Provide a valid URL to access CellBase web services.");
            }
            // Left align in remote mode can only be enabled if a reference fasta is provided
            if (leftAlign) {
                if (referenceFasta == null) {
                    throw new ParameterException("Please provide a valid reference fasta file. Left align when annotating"
                            + " in remote mode (--local flag NOT present) can only be enabled if a fasta file with"
                            + " the reference genome sequence is provided within --reference-fasta. Alternatively"
                            + " you can disable left align by using --skip-left-align.");
                }
            }
        // --local flag enabled
        // Use of --reference-fasta and --local together will cause --reference-fasta to override the reference genome
        // in CellBase database (DISCOURAGED!)
        } else if (leftAlign && referenceFasta != null) {
            logger.warn("--reference-fasta and --local parameters found together. This is strongly discouraged. Please"
                    + " NOTE: the sequence within the fasta file will override CellBase reference sequence.");
        }

        // Species
        if (variantAnnotationCommandOptions.species != null) {
            species = variantAnnotationCommandOptions.species;
        } else {
            throw new ParameterException("Please check command line syntax. Provide a valid species name.");
        }

        // Assembly
        if (variantAnnotationCommandOptions.assembly != null) {
            assembly = variantAnnotationCommandOptions.assembly;
            // In case annotation is made through WS assembly must be set in the queryOptions
            queryOptions.put("assembly", variantAnnotationCommandOptions.assembly);
        } else {
            assembly = null;
            logger.warn("No assembly provided. Using default assembly for {}", species);
        }

        // Custom files
        if (variantAnnotationCommandOptions.customFiles != null) {
            String[] customFileStrings = variantAnnotationCommandOptions.customFiles.split(",");
            customFiles = new ArrayList<>(customFileStrings.length);
            for (String customFile : customFileStrings) {
                Path customFilePath = Paths.get(customFile);
                FileUtils.checkFile(customFilePath);
                if (!(customFilePath.toString().endsWith(".vcf") || customFilePath.toString().endsWith(".vcf.gz"))) {
                    throw new ParameterException("Only VCF format is currently accepted for custom annotation files.");
                }
                customFiles.add(customFilePath);
            }
            if (variantAnnotationCommandOptions.customFileIds == null) {
                throw new ParameterException("Parameter --custom-file-ids missing. Please, provide one short id for each custom file in "
                        + "a comma separated list (no spaces in betwen).");
            }
            customFileIds = Arrays.asList(variantAnnotationCommandOptions.customFileIds.split(","));
            if (customFileIds.size() != customFiles.size()) {
                throw new ParameterException("Different number of custom files and custom file ids. Please, "
                        + "provide one short id for each custom file in a comma separated list (no spaces in between).");
            }
            if (variantAnnotationCommandOptions.customFileFields == null) {
                throw new ParameterException("Parameter --custom-file-fields missing. Please, provide one list of fields for each "
                        + "custom file in a colon separated list (no spaces in betwen).");
            }
            String[] customFileFieldStrings = variantAnnotationCommandOptions.customFileFields.split(":");
            if (customFileFieldStrings.length != customFiles.size()) {
                throw new ParameterException("Different number of custom files and lists of custom file fields. "
                        + "Please, provide one list of fields for each custom file in a colon separated list (no spaces in between).");
            }
            customFileFields = new ArrayList<>(customFileStrings.length);
            for (String fieldString : customFileFieldStrings) {
                customFileFields.add(Arrays.asList(fieldString.split(",")));
            }
            // MaxOpenFiles parameter for RocksDB indexation of custom files
            maxOpenFiles = variantAnnotationCommandOptions.maxOpenFiles;
        }

        // Semi-private build parameter for us to build the variation collection including population frequencies
        if (variantAnnotationCommandOptions.buildParams.get("population-frequencies") != null) {
            populationFrequenciesFile = Paths.get(variantAnnotationCommandOptions.buildParams.get("population-frequencies"));
            FileUtils.checkFile(populationFrequenciesFile);
            if (!(populationFrequenciesFile.toString().endsWith(".json")
                    || populationFrequenciesFile.toString().endsWith(".json.gz"))) {
                throw new ParameterException("Population frequencies file must be a .json (.json.gz) file containing"
                        + " Variant objects.");
            }
            completeInputPopulation = Boolean.valueOf(variantAnnotationCommandOptions.buildParams.get("complete-input-population"));
        }

        // Enable/Disable imprecise annotation
        queryOptions.put("imprecise", !variantAnnotationCommandOptions.noImprecision);

        // Parameter not expected to be very used - provide extra padding (bp) to be used for structural variant annotation
        if (variantAnnotationCommandOptions.buildParams.get("sv-extra-padding") != null) {
            Integer svExtraPadding = Integer.valueOf(variantAnnotationCommandOptions.buildParams.get("sv-extra-padding"));
            if (svExtraPadding < 0) {
                throw new ParameterException("Extra padding for SV annotation cannot be < 0, value provided: "
                        + svExtraPadding + ". Please provide a value >= 0");
            }
            queryOptions.put("svExtraPadding", svExtraPadding);
        }

        // Parameter not expected to be very used - provide extra padding (bp) to be used for CNV annotation
        if (variantAnnotationCommandOptions.buildParams.get("cnv-extra-padding") != null) {
            Integer cnvExtraPadding = Integer.valueOf(variantAnnotationCommandOptions.buildParams.get("cnv-extra-padding"));
            if (cnvExtraPadding < 0) {
                throw new ParameterException("Extra padding for CNV annotation cannot be < 0, value provided: "
                        + cnvExtraPadding + ". Please provide a value >= 0");
            }
            queryOptions.put("cnvExtraPadding", cnvExtraPadding);
        }

        // Annotate variation collection in CellBase
        cellBaseAnnotation = variantAnnotationCommandOptions.cellBaseAnnotation;

        // The list of chromosomes will only be used if annotating the variation collection
        if (cellBaseAnnotation) {
            // This will set chromosomeList with the list of chromosomes to annotate
            setChromosomeList();
        }

    }


}
