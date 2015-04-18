package org.opencb.cellbase.mongodb.db;

import org.opencb.cellbase.core.CellBaseConfiguration;
import org.opencb.cellbase.core.lib.DBAdaptorFactory;
import org.opencb.cellbase.core.lib.api.CpGIslandDBAdaptor;
import org.opencb.cellbase.core.lib.api.CytobandDBAdaptor;
import org.opencb.cellbase.core.lib.api.core.*;
import org.opencb.cellbase.core.lib.api.regulatory.MirnaDBAdaptor;
import org.opencb.cellbase.core.lib.api.regulatory.RegulatoryRegionDBAdaptor;
import org.opencb.cellbase.core.lib.api.regulatory.TfbsDBAdaptor;
import org.opencb.cellbase.core.lib.api.systems.PathwayDBAdaptor;
import org.opencb.cellbase.core.lib.api.systems.ProteinProteinInteractionDBAdaptor;
import org.opencb.cellbase.core.lib.api.variation.*;
import org.opencb.cellbase.mongodb.db.network.PathwayMongoDBAdaptor;
import org.opencb.cellbase.mongodb.db.network.ProteinProteinInteractionMongoDBAdaptor;
import org.opencb.cellbase.mongodb.db.regulatory.RegulatoryRegionMongoDBAdaptor;
import org.opencb.cellbase.mongodb.db.regulatory.TfbsMongoDBAdaptor;
import org.opencb.datastore.mongodb.MongoDBConfiguration;
import org.opencb.datastore.mongodb.MongoDataStore;
import org.opencb.datastore.mongodb.MongoDataStoreManager;
import org.slf4j.LoggerFactory;

public class MongoDBAdaptorFactory extends DBAdaptorFactory {

    /**
     * MongoDataStoreManager acts as singleton by keeping a reference to all databases connections created.
     */
    private static MongoDataStoreManager mongoDataStoreManager;
//    private static Map<String, MongoDataStore> mongoDatastoreFactory;

    public MongoDBAdaptorFactory(CellBaseConfiguration cellBaseConfiguration){
        super(cellBaseConfiguration);

        mongoDataStoreManager = new MongoDataStoreManager(
                cellBaseConfiguration.getDatabase().getHost(),
                Integer.parseInt(cellBaseConfiguration.getDatabase().getPort())
        );

        logger = LoggerFactory.getLogger(this.getClass());
        logger.info("MongoDBAdaptorFactory constructor, this should be only be printed once");
    }

    private MongoDataStore createMongoDBDatastore(String species, String assembly) {
        /**
         Database name has the following pattern in lower case:
         cellbase_speciesId_assembly_cellbaseVersion
         Example:
         cellbase_hsapiens_grch37_v3
         **/

        CellBaseConfiguration.SpeciesProperties.Species speciesObject = getSpecies(species);
        if(speciesObject != null) {
            species = speciesObject.getId();
            assembly = getAssembly(speciesObject, assembly).toLowerCase();

            if (species != null && !species.isEmpty() && assembly != null && !assembly.isEmpty()) {

                // if it does not exist then we create and store it
                // Database name is built following the above pattern
                String database = "cellbase" + "_" + species + "_" + assembly + "_" + cellBaseConfiguration.getVersion();
                logger.debug("Database for the species is '{}'", database);

                MongoDBConfiguration mongoDBConfiguration;
                // For authenticated databases
                if(!cellBaseConfiguration.getDatabase().getUser().equals("")
                        && !cellBaseConfiguration.getDatabase().getPassword().equals("")) {
                    // MongoDB could authenticate against different databases
                    if(cellBaseConfiguration.getDatabase().getOptions().containsKey("authenticationDatabase")) {
                        mongoDBConfiguration = MongoDBConfiguration.builder()
                                .add("username", cellBaseConfiguration.getDatabase().getUser())
                                .add("password", cellBaseConfiguration.getDatabase().getPassword())
                                .add("authenticationDatabase", cellBaseConfiguration.getDatabase().getOptions().get("authenticationDatabase"))
                                .build();
                    } else {
                        mongoDBConfiguration = MongoDBConfiguration.builder()
                                .add("username", cellBaseConfiguration.getDatabase().getUser())
                                .add("password", cellBaseConfiguration.getDatabase().getPassword())
                                .build();
                    }
                } else {
                    mongoDBConfiguration = MongoDBConfiguration.builder().init().build();
                }

                // A MongoDataStore to this host and database is returned
                MongoDataStore mongoDatastore = mongoDataStoreManager.get(database, mongoDBConfiguration);

                // we return the MongoDataStore object
                return mongoDatastore;
            } else {
                logger.error("Species name or assembly are not valid, species '{}', assembly '{}'", species, assembly);
                return null;
            }
        } else {
            logger.error("Species name is not valid: '{}'", species);
            return null;
        }
    }

//    private String getSpeciesAssemblyId(String species, String assembly) {
//        String speciesAssemblyid = null;
//        if (species != null && !species.isEmpty() && assembly != null && !assembly.isEmpty()) {
//            speciesAssemblyid = species + "_" + assembly.toLowerCase();
//        }
//        return speciesAssemblyid;
//    }

    @Override
    public void setConfiguration(CellBaseConfiguration cellBaseConfiguration) {
        if (cellBaseConfiguration != null) {
            this.cellBaseConfiguration = cellBaseConfiguration;
        }
    }

    @Override
    public void open(String species, String assembly) {

    }

    @Override
    public void close() {

    }

    @Override
    public GenomeSequenceDBAdaptor getGenomeSequenceDBAdaptor(String species) {
        return getGenomeSequenceDBAdaptor(species, null);
    }

    @Override
    public GenomeSequenceDBAdaptor getGenomeSequenceDBAdaptor(String species, String assembly) {
        MongoDataStore mongoDataStore = createMongoDBDatastore(species, assembly);
        return new GenomeSequenceMongoDBAdaptor(species, assembly, mongoDataStore);
    }


    @Override
    public ChromosomeDBAdaptor getChromosomeDBAdaptor(String species) {
        return getChromosomeDBAdaptor(species, null);
    }

    @Override
    public ChromosomeDBAdaptor getChromosomeDBAdaptor(String species, String assembly) {
        MongoDataStore mongoDatastore = createMongoDBDatastore(species, assembly);
        return new ChromosomeMongoDBAdaptor(species, assembly, mongoDatastore);
    }


    @Override
    public ConservedRegionDBAdaptor getConservedRegionDBAdaptor(String species) {
        return getConservedRegionDBAdaptor(species, null);
    }

    @Override
    public ConservedRegionDBAdaptor getConservedRegionDBAdaptor(String species, String assembly) {
        MongoDataStore mongoDatastore = createMongoDBDatastore(species, assembly);
        return new ConservationMongoDBAdaptor(species, assembly, mongoDatastore);
    }


    @Override
    public ExonDBAdaptor getExonDBAdaptor(String species) {
        return getExonDBAdaptor(species, null);
    }

    @Override
    public ExonDBAdaptor getExonDBAdaptor(String species, String assembly) {
        MongoDataStore mongoDatastore = createMongoDBDatastore(species, assembly);
        return new ExonMongoDBAdaptor(species, assembly, mongoDatastore);
    }


    @Override
    public TranscriptDBAdaptor getTranscriptDBAdaptor(String species) {
        return getTranscriptDBAdaptor(species, null);
    }

    @Override
    public TranscriptDBAdaptor getTranscriptDBAdaptor(String species, String assembly) {
        MongoDataStore mongoDatastore = createMongoDBDatastore(species, assembly);
        return new TranscriptMongoDBAdaptor(species, assembly, mongoDatastore);
    }


    @Override
    public GeneDBAdaptor getGeneDBAdaptor(String species) {
        return getGeneDBAdaptor(species, null);
    }

    @Override
    public GeneDBAdaptor getGeneDBAdaptor(String species, String assembly) {
        MongoDataStore mongoDatastore = createMongoDBDatastore(species, assembly);
        return new GeneMongoDBAdaptor(species, assembly, mongoDatastore);
    }


    @Override
    public XRefsDBAdaptor getXRefDBAdaptor(String species) {
        return getXRefDBAdaptor(species, null);
    }

    @Override
    public XRefsDBAdaptor getXRefDBAdaptor(String species, String assembly) {
        MongoDataStore mongoDatastore = createMongoDBDatastore(species, assembly);
        return new XRefsMongoDBAdaptor(species, assembly, mongoDatastore);
    }


    @Override
    public VariationDBAdaptor getVariationDBAdaptor(String species) {
        return getVariationDBAdaptor(species, null);
    }

    @Override
    public VariationDBAdaptor getVariationDBAdaptor(String species, String assembly) {
        MongoDataStore mongoDatastore = createMongoDBDatastore(species, assembly);
        return new VariationMongoDBAdaptor(species, assembly, mongoDatastore);
    }


    @Override
    public VariantAnnotationDBAdaptor getVariantAnnotationDBAdaptor(String species) {
        return getVariantAnnotationDBAdaptor(species, null);
    }

    @Override
    public VariantAnnotationDBAdaptor getVariantAnnotationDBAdaptor(String species, String assembly) {
        MongoDataStore mongoDatastore = createMongoDBDatastore(species, assembly);
        VariantAnnotationDBAdaptor variantAnnotationDBAdaptor = new VariantAnnotationMongoDBAdaptor(species, assembly,
                mongoDatastore);
        variantAnnotationDBAdaptor.setGeneDBAdaptor(getGeneDBAdaptor(species, assembly));
        variantAnnotationDBAdaptor.setRegulatoryRegionDBAdaptor(getRegulatoryRegionDBAdaptor(species, assembly));
        variantAnnotationDBAdaptor.setVariationDBAdaptor(getVariationDBAdaptor(species, assembly));
        variantAnnotationDBAdaptor.setVariantClinicalDBAdaptor(getClinicalDBAdaptor(species, assembly));
        variantAnnotationDBAdaptor.setProteinFunctionPredictorDBAdaptor(getProteinFunctionPredictorDBAdaptor(species, assembly));
        variantAnnotationDBAdaptor.setConservedRegionDBAdaptor(getConservedRegionDBAdaptor(species, assembly));

        return variantAnnotationDBAdaptor;
    }


    @Override
    public ClinicalDBAdaptor getClinicalDBAdaptor(String species) {
        return getClinicalDBAdaptor(species, null);
    }

    @Override
    public ClinicalDBAdaptor getClinicalDBAdaptor(String species, String assembly) {
        MongoDataStore mongoDatastore = createMongoDBDatastore(species, assembly);
        return new ClinicalMongoDBAdaptor(species, assembly, mongoDatastore);
    }


    @Override
    public ProteinDBAdaptor getProteinDBAdaptor(String species) {
        return getProteinDBAdaptor(species, null);
    }

    @Override
    public ProteinDBAdaptor getProteinDBAdaptor(String species, String assembly) {
        MongoDataStore mongoDatastore = createMongoDBDatastore(species, assembly);
        return new ProteinMongoDBAdaptor(species, assembly, mongoDatastore);
    }


    @Override
    public ProteinFunctionPredictorDBAdaptor getProteinFunctionPredictorDBAdaptor(String species) {
        return getProteinFunctionPredictorDBAdaptor(species, null);
    }

    @Override
    public ProteinFunctionPredictorDBAdaptor getProteinFunctionPredictorDBAdaptor(String species, String assembly) {
        MongoDataStore mongoDatastore = createMongoDBDatastore(species, assembly);
        return new ProteinFunctionPredictorMongoDBAdaptor(species, assembly, mongoDatastore);
    }


    @Override
    public ProteinProteinInteractionDBAdaptor getProteinProteinInteractionDBAdaptor(String species) {
        return getProteinProteinInteractionDBAdaptor(species, null);
    }

    @Override
    public ProteinProteinInteractionDBAdaptor getProteinProteinInteractionDBAdaptor(String species, String assembly) {
        MongoDataStore mongoDatastore = createMongoDBDatastore(species, assembly);
        return new ProteinProteinInteractionMongoDBAdaptor(species, assembly, mongoDatastore);
    }


    @Override
    public RegulatoryRegionDBAdaptor getRegulatoryRegionDBAdaptor(String species) {
        return getRegulatoryRegionDBAdaptor(species, null);
    }

    @Override
    public RegulatoryRegionDBAdaptor getRegulatoryRegionDBAdaptor(String species, String assembly) {
        MongoDataStore mongoDatastore = createMongoDBDatastore(species, assembly);
        return new RegulatoryRegionMongoDBAdaptor(species, assembly, mongoDatastore);
    }


    @Override
    public TfbsDBAdaptor getTfbsDBAdaptor(String species) {
        return getTfbsDBAdaptor(species, null);
    }

    @Override
    public TfbsDBAdaptor getTfbsDBAdaptor(String species, String assembly) {
        MongoDataStore mongoDatastore = createMongoDBDatastore(species, assembly);
        return new TfbsMongoDBAdaptor(species, assembly, mongoDatastore);
    }


    @Override
    public PathwayDBAdaptor getPathwayDBAdaptor(String species) {
        return getPathwayDBAdaptor(species, null);
    }

    @Override
    public PathwayDBAdaptor getPathwayDBAdaptor(String species, String assembly) {
        MongoDataStore mongoDatastore = createMongoDBDatastore(species, assembly);
        return new PathwayMongoDBAdaptor(species, assembly, mongoDatastore);
    }



    @Override
    public VariationPhenotypeAnnotationDBAdaptor getVariationPhenotypeAnnotationDBAdaptor(String species) {
        return getVariationPhenotypeAnnotationDBAdaptor(species, null);
    }

    @Override
    public VariationPhenotypeAnnotationDBAdaptor getVariationPhenotypeAnnotationDBAdaptor(String species, String assembly) {
        MongoDataStore mongoDatastore = createMongoDBDatastore(species, assembly);
        return (VariationPhenotypeAnnotationDBAdaptor) new VariationPhenotypeAnnotationMongoDBAdaptor(species, assembly, mongoDatastore);
    }

    @Override
    public CpGIslandDBAdaptor getCpGIslandDBAdaptor(String species) {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public CpGIslandDBAdaptor getCpGIslandDBAdaptor(String species, String assembly) {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public StructuralVariationDBAdaptor getStructuralVariationDBAdaptor(String species) {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public StructuralVariationDBAdaptor getStructuralVariationDBAdaptor(String species, String assembly) {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public SnpDBAdaptor getSnpDBAdaptor(String species) {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public SnpDBAdaptor getSnpDBAdaptor(String species, String assembly) {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public CytobandDBAdaptor getCytobandDBAdaptor(String species) {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public MirnaDBAdaptor getMirnaDBAdaptor(String species) {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public MirnaDBAdaptor getMirnaDBAdaptor(String species, String assembly) {
        // TODO Auto-generated method stub
        return null;
    }

    @Deprecated
    @Override
    public VariantEffectDBAdaptor getGenomicVariantEffectDBAdaptor(String species) {
        return getGenomicVariantEffectDBAdaptor(species, null);
    }

    @Deprecated
    @Override
    public VariantEffectDBAdaptor getGenomicVariantEffectDBAdaptor(String species, String assembly) {
        MongoDataStore mongoDatastore = createMongoDBDatastore(species, assembly);
        return new VariantEffectMongoDBAdaptor(species, assembly, mongoDatastore);
    }

    @Override
    public MutationDBAdaptor getMutationDBAdaptor(String species) {
        return getMutationDBAdaptor(species, null);
    }

    @Deprecated
    @Override
    public MutationDBAdaptor getMutationDBAdaptor(String species, String assembly) {
        MongoDataStore mongoDatastore = createMongoDBDatastore(species, assembly);
        return (MutationDBAdaptor) new MutationMongoDBAdaptor(species, assembly, mongoDatastore);
    }

    @Override
    public ClinVarDBAdaptor getClinVarDBAdaptor(String species) {
        return getClinVarDBAdaptor(species, null);
    }

    @Override
    public ClinVarDBAdaptor getClinVarDBAdaptor(String species, String assembly) {
        MongoDataStore mongoDatastore = createMongoDBDatastore(species, assembly);
        return (ClinVarDBAdaptor) new ClinVarMongoDBAdaptor(species, assembly, mongoDatastore);
    }

    @Override
    public CytobandDBAdaptor getCytobandDBAdaptor(String species, String assembly) {
        // TODO Auto-generated method stub
        return null;
    }
}
