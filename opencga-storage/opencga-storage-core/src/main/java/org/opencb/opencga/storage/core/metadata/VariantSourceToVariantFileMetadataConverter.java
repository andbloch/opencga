package org.opencb.opencga.storage.core.metadata;

import org.opencb.biodata.models.variant.VariantFileMetadata;
import org.opencb.biodata.models.variant.avro.legacy.VariantSource;
import org.opencb.biodata.models.variant.avro.legacy.VcfHeader;
import org.opencb.biodata.models.variant.metadata.VariantFileHeader;
import org.opencb.biodata.models.variant.metadata.VariantFileHeaderComplexLine;
import org.opencb.biodata.models.variant.metadata.VariantFileHeaderSimpleLine;
import org.opencb.biodata.models.variant.stats.VariantSetStats;
import org.opencb.biodata.tools.Converter;

import java.util.HashMap;
import java.util.LinkedList;
import java.util.Map;

/**
 * Created on 07/09/17.
 *
 * @author Jacobo Coll &lt;jacobo167@gmail.com&gt;
 */
public class VariantSourceToVariantFileMetadataConverter implements Converter<VariantSource, VariantFileMetadata> {

    public VariantSourceToVariantFileMetadataConverter() {
    }

    public VariantFileMetadata convert(org.opencb.biodata.models.variant.VariantSource legacy) {
        return convert(legacy.getImpl());
    }

    @Override
    public VariantFileMetadata convert(VariantSource legacy) {
        VariantFileMetadata fileMetadata = new VariantFileMetadata(legacy.getFileId(), legacy.getFileName());
        fileMetadata.setSampleIds(legacy.getSamples());

        org.opencb.biodata.models.variant.avro.legacy.VariantGlobalStats legacyStats = legacy.getStats();

        if (legacyStats != null) {
            VariantSetStats variantSetStats = convertStats(legacyStats);
            fileMetadata.setStats(variantSetStats);
        }


        Map<String, String> attributes = new HashMap<>();
        legacy.getMetadata().forEach((key, value) -> {
            if (!"variantFileHeader".equals(key)) {
                attributes.put(key, String.valueOf(value));
            }
        });
        fileMetadata.setAttributes(attributes);

        VcfHeader vcfHeader = legacy.getHeader();
        VariantFileHeader variantFileHeader = new VariantFileHeader(vcfHeader.getFileFormat(), new LinkedList<>(), new LinkedList<>());
        vcfHeader.getMeta().forEach((key, values) -> {
            for (Object value : values) {
                if (value instanceof String) {
                    variantFileHeader.getSimpleLines().add(new VariantFileHeaderSimpleLine(key, ((String) value)));
                } else if (value instanceof Map) {
                    Map<String, String> map = (Map<String, String>) value;
                    variantFileHeader.getComplexLines().add(new VariantFileHeaderComplexLine(key,
                            takeFromMap(map, "ID"),
                            takeFromMap(map, "Description"),
                            takeFromMap(map, "Number"),
                            takeFromMap(map, "Type"),
                            map
                            ));
                }
            }
        });
        fileMetadata.setHeader(variantFileHeader);

        return fileMetadata;
    }

    public VariantSetStats convertStats(org.opencb.biodata.models.variant.avro.legacy.VariantGlobalStats legacyStats) {
        return new VariantSetStats(org.opencb.biodata.models.variant.metadata.VariantSetStats.newBuilder()
                .setNumVariants(legacyStats.getNumRecords())
                .setMeanQuality(legacyStats.getMeanQuality().floatValue())
                .setNumPass(legacyStats.getPassCount())
                .setNumSamples(legacyStats.getSamplesCount())
                .setTiTvRatio(legacyStats.getTransitionsCount() / (float) legacyStats.getTransversionsCount())
                .setVariantTypeCounts(legacyStats.getVariantTypeCounts())
                .setConsequenceTypesCounts(legacyStats.getConsequenceTypesCount())
                .setStdDevQuality(0)
                .setChromosomeCounts(legacyStats.getChromosomeCounts()).build());
    }

    protected String takeFromMap(Map<String, String> map, String key) {
        String value = map.remove(key);
        if (value != null) {
            return value;
        } else {
            return null;
        }
    }
}
