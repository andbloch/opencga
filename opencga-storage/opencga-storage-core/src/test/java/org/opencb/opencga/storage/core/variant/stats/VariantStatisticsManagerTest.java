/*
 * Copyright 2015-2017 OpenCB
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

package org.opencb.opencga.storage.core.variant.stats;

import org.apache.commons.lang3.StringUtils;
import org.junit.*;
import org.junit.rules.ExpectedException;
import org.opencb.biodata.models.feature.Genotype;
import org.opencb.biodata.models.variant.StudyEntry;
import org.opencb.biodata.models.variant.Variant;
import org.opencb.biodata.models.variant.stats.VariantStats;
import org.opencb.biodata.tools.variant.stats.VariantStatsCalculator;
import org.opencb.commons.datastore.core.ObjectMap;
import org.opencb.commons.datastore.core.Query;
import org.opencb.commons.datastore.core.QueryOptions;
import org.opencb.opencga.storage.core.exceptions.StorageEngineException;
import org.opencb.opencga.storage.core.metadata.VariantStorageMetadataManager;
import org.opencb.opencga.storage.core.metadata.models.CohortMetadata;
import org.opencb.opencga.storage.core.metadata.models.SampleMetadata;
import org.opencb.opencga.storage.core.metadata.models.StudyMetadata;
import org.opencb.opencga.storage.core.variant.VariantStorageBaseTest;
import org.opencb.opencga.storage.core.variant.VariantStorageEngine;
import org.opencb.opencga.storage.core.variant.VariantStorageEngineTest;
import org.opencb.opencga.storage.core.variant.adaptors.VariantDBAdaptor;
import org.opencb.opencga.storage.core.variant.adaptors.iterators.VariantDBIterator;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Created by hpccoll1 on 01/06/15.
 */
@Ignore
public abstract class VariantStatisticsManagerTest extends VariantStorageBaseTest {

    public static final String VCF_TEST_FILE_NAME = "variant-test-file.vcf.gz";
    private StudyMetadata studyMetadata;
    private VariantDBAdaptor dbAdaptor;

    @Rule
    public ExpectedException thrown = ExpectedException.none();
    private VariantStorageMetadataManager metadataManager;

    @BeforeClass
    public static void beforeClass() throws IOException {
        Path rootDir = getTmpRootDir();
        Path inputPath = rootDir.resolve(VCF_TEST_FILE_NAME);
        Files.copy(VariantStorageEngineTest.class.getClassLoader().getResourceAsStream(VCF_TEST_FILE_NAME), inputPath,
                StandardCopyOption.REPLACE_EXISTING);
        inputUri = inputPath.toUri();
    }

    @Override
    @Before
    public void before() throws Exception {
        studyMetadata = newStudyMetadata();
        clearDB(DB_NAME);
        runDefaultETL(inputUri, getVariantStorageEngine(), studyMetadata,
                new ObjectMap(VariantStorageEngine.Options.ANNOTATE.key(), false));
        dbAdaptor = getVariantStorageEngine().getDBAdaptor();
        metadataManager = dbAdaptor.getMetadataManager();
    }

    @Test
    public void calculateStatsMultiCohortsTest() throws Exception {
        //Calculate stats for 2 cohorts at one time
        checkCohorts(dbAdaptor, studyMetadata);

        QueryOptions options = new QueryOptions();
        options.put(VariantStorageEngine.Options.LOAD_BATCH_SIZE.key(), 100);
        Iterator<SampleMetadata> iterator = metadataManager.sampleMetadataIterator(studyMetadata.getId());

        /** Create cohorts **/
        HashSet<String> cohort1 = new HashSet<>();
        cohort1.add(iterator.next().getName());
        cohort1.add(iterator.next().getName());

        HashSet<String> cohort2 = new HashSet<>();
        cohort2.add(iterator.next().getName());
        cohort2.add(iterator.next().getName());

        Map<String, Set<String>> cohorts = new HashMap<>();
        cohorts.put("cohort1", cohort1);
        cohorts.put("cohort2", cohort2);

        //Calculate stats
        stats(options, studyMetadata, cohorts, outputUri.resolve("cohort1.cohort2.stats"));

        checkCohorts(dbAdaptor, studyMetadata);
    }

    @Test
    public void calculateStatsSeparatedCohortsTest() throws Exception {
        //Calculate stats for 2 cohorts separately

        String studyName = studyMetadata.getStudyName();
        QueryOptions options = new QueryOptions();
        options.put(VariantStorageEngine.Options.LOAD_BATCH_SIZE.key(), 100);
        Iterator<SampleMetadata> iterator = metadataManager.sampleMetadataIterator(studyMetadata.getId());
        StudyMetadata studyMetadata;

        /** Create first cohort **/
        studyMetadata = metadataManager.getStudyMetadata(studyName);
        HashSet<String> cohort1 = new HashSet<>();
        cohort1.add(iterator.next().getName());
        cohort1.add(iterator.next().getName());

        Map<String, Set<String>> cohorts;

        cohorts = new HashMap<>();
        cohorts.put("cohort1", cohort1);

        //Calculate stats for cohort1
        studyMetadata = stats(options, studyMetadata, cohorts, outputUri.resolve("cohort1.stats"));

        CohortMetadata cohort1Metadata = metadataManager.getCohortMetadata(studyMetadata.getId(), "cohort1");
//        assertThat(studyMetadata.getCalculatedStats(), hasItem(cohort1Id));
        assertTrue(cohort1Metadata.isReady());
        checkCohorts(dbAdaptor, studyMetadata);

        /** Create second cohort **/
        studyMetadata = metadataManager.getStudyMetadata(studyName);
        HashSet<String> cohort2 = new HashSet<>();
        cohort2.add(iterator.next().getName());
        cohort2.add(iterator.next().getName());

        cohorts = new HashMap<>();
        cohorts.put("cohort2", cohort2);

        //Calculate stats for cohort2
        studyMetadata = stats(options, studyMetadata, cohorts, outputUri.resolve("cohort2.stats"));

        cohort1Metadata = metadataManager.getCohortMetadata(studyMetadata.getId(), "cohort1");
        CohortMetadata cohort2Metadata = metadataManager.getCohortMetadata(studyMetadata.getId(), "cohort1");
        assertTrue(cohort1Metadata.isReady());
        assertTrue(cohort2Metadata.isReady());

        checkCohorts(dbAdaptor, studyMetadata);

        //Try to recalculate stats for cohort2. Will fail
        studyMetadata = metadataManager.getStudyMetadata(studyName);
        thrown.expect(StorageEngineException.class);
        stats(options, studyMetadata, cohorts, outputUri.resolve("cohort2.stats"));

    }

    public StudyMetadata stats(QueryOptions options, StudyMetadata studyMetadata, Map<String, Set<String>> cohorts,
                                    URI output) throws IOException, StorageEngineException {
        options.put(DefaultVariantStatisticsManager.OUTPUT, output.toString());
        variantStorageEngine.calculateStats(studyMetadata.getStudyName(), cohorts, options);
        return metadataManager.getStudyMetadata(studyMetadata.getStudyId());
    }

    public static StudyMetadata stats(VariantStatisticsManager vsm, QueryOptions options, StudyMetadata studyMetadata,
                                           Map<String, Set<String>> cohorts, Map<String, Integer> cohortIds, VariantDBAdaptor dbAdaptor,
                                           URI resolve) throws IOException, StorageEngineException {
        if (vsm instanceof DefaultVariantStatisticsManager) {
            DefaultVariantStatisticsManager dvsm = (DefaultVariantStatisticsManager) vsm;
            URI stats = dvsm.createStats(dbAdaptor, resolve, cohorts, cohortIds, studyMetadata, options);
            dvsm.loadStats(dbAdaptor, stats, studyMetadata, options);
        } else {
            dbAdaptor.getMetadataManager().registerCohorts(studyMetadata.getName(), cohorts);
//            studyMetadata.getCohortIds().putAll(cohortIds);
//            cohorts.forEach((cohort, samples) -> {
//                Set<Integer> sampleIds = samples.stream().map(studyMetadata.getSampleIds()::get).collect(Collectors.toSet());
//                studyMetadata.getCohorts().put(cohortIds.get(cohort), sampleIds);
//            });
//            dbAdaptor.getMetadataManager().updateStudyMetadata(studyMetadata);
            vsm.calculateStatistics(studyMetadata.getStudyName(), new ArrayList<>(cohorts.keySet()), options);
        }
        return dbAdaptor.getMetadataManager().getStudyMetadata(studyMetadata.getStudyId());
    }

    static void checkCohorts(VariantDBAdaptor dbAdaptor, StudyMetadata studyMetadata) {
        Map<String, CohortMetadata> cohorts = new HashMap<>();
        dbAdaptor.getMetadataManager().cohortIterator(studyMetadata.getId()).forEachRemaining(cohort -> {
            cohorts.put(cohort.getName(), cohort);
        });
        for (VariantDBIterator iterator = dbAdaptor.iterator(new Query(), null);
             iterator.hasNext(); ) {
            Variant variant = iterator.next();
            for (StudyEntry sourceEntry : variant.getStudies()) {
                Map<String, VariantStats> cohortsStats = sourceEntry.getStats();
                String calculatedCohorts = cohortsStats.keySet().toString();
                for (Map.Entry<String, CohortMetadata> entry : cohorts.entrySet()) {
                    CohortMetadata cohort = entry.getValue();
                    if (!cohort.isReady()) {
                        continue;
                    }
                    assertTrue("CohortStats should contain stats for cohort '" + entry.getKey() + "'. Contains stats for " +
                                    calculatedCohorts,
                            cohortsStats.containsKey(entry.getKey()));    //Check stats are calculated

                    VariantStats cohortStats = cohortsStats.get(entry.getKey());
                    assertEquals("Stats for cohort " + entry.getKey() + " have less genotypes than expected. "
                                    + cohortStats.getGenotypeCount(),
                            cohort.getSamples().size(),  //Check numGenotypes are correct (equals to
                            // the number of samples)
                            cohortStats.getGenotypeCount().values().stream().reduce(0, (a, b) -> a + b).intValue());

                    HashMap<Genotype, Integer> genotypeCount = new HashMap<>();
                    for (Integer sampleId : cohort.getSamples()) {
                        String sampleName = dbAdaptor.getMetadataManager().getSampleName(studyMetadata.getId(), sampleId);
                        String gt = sourceEntry.getSampleData(sampleName, "GT");
                        genotypeCount.compute(new Genotype(gt), (key, value) -> value == null ? 1 : value + 1);
                    }

                    VariantStats stats = VariantStatsCalculator.calculate(variant, genotypeCount);

                    stats.getGenotypeCount().entrySet().removeIf(e -> e.getValue() == 0);
                    stats.getGenotypeFreq().entrySet().removeIf(e -> e.getValue() == 0);
                    cohortStats.getGenotypeCount().entrySet().removeIf(e -> e.getValue() == 0);
                    cohortStats.getGenotypeFreq().entrySet().removeIf(e -> e.getValue() == 0);

                    assertEquals(variant.toString(), stats.getGenotypeCount(), cohortStats.getGenotypeCount());
                    assertEquals(variant.toString(), stats.getGenotypeFreq(), cohortStats.getGenotypeFreq());
                    assertEquals(variant.toString(), stats.getMaf(), cohortStats.getMaf());
                    if (StringUtils.isNotEmpty(stats.getMafAllele()) || StringUtils.isNotEmpty(cohortStats.getMafAllele())) {
                        assertEquals(variant.toString(), stats.getMafAllele(), cohortStats.getMafAllele());
                    }
                    assertEquals(variant.toString(), stats.getMgf(), cohortStats.getMgf());
                    assertEquals(variant.toString(), stats.getRefAlleleFreq(), cohortStats.getRefAlleleFreq());
                    assertEquals(variant.toString(), stats.getAltAlleleFreq(), cohortStats.getAltAlleleFreq());
                }
            }
        }
    }

}
