/*
 * Copyright 2015-2020 OpenCB
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

package org.opencb.opencga.analysis.clinical;

import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.junit.*;
import org.opencb.biodata.models.clinical.ClinicalProperty;
import org.opencb.biodata.models.clinical.interpretation.Interpretation;
import org.opencb.commons.datastore.core.ObjectMap;
import org.opencb.commons.datastore.core.Query;
import org.opencb.opencga.analysis.clinical.team.TeamInterpretationAnalysis;
import org.opencb.opencga.analysis.clinical.team.TeamInterpretationConfiguration;
import org.opencb.opencga.analysis.clinical.tiering.TieringInterpretationAnalysis;
import org.opencb.opencga.analysis.clinical.tiering.TieringInterpretationConfiguration;
import org.opencb.opencga.analysis.clinical.zetta.ZettaInterpretationAnalysis;
import org.opencb.opencga.analysis.clinical.zetta.ZettaInterpretationConfiguration;
import org.opencb.opencga.analysis.variant.OpenCGATestExternalResource;
import org.opencb.opencga.catalog.managers.AbstractClinicalManagerTest;
import org.opencb.opencga.catalog.managers.CatalogManagerExternalResource;
import org.opencb.opencga.core.exceptions.ToolException;
import org.opencb.opencga.core.tools.result.ExecutionResult;
import org.opencb.opencga.storage.core.variant.VariantStorageBaseTest;
import org.opencb.opencga.storage.core.variant.adaptors.VariantQueryParam;
import org.opencb.opencga.storage.mongodb.variant.MongoDBVariantStorageTest;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.fail;

public class ClinicalInterpretationAnalysisTest extends VariantStorageBaseTest implements MongoDBVariantStorageTest {


    private AbstractClinicalManagerTest clinicalTest;

    Path outDir;


    @ClassRule
    public static OpenCGATestExternalResource opencga = new OpenCGATestExternalResource();

    @Rule
    public CatalogManagerExternalResource catalogManagerResource = new CatalogManagerExternalResource();

    @Before
    public void setUp() throws Exception {
        clearDB("opencga_test_user_1000G");
        clinicalTest = ClinicalAnalysisUtilsTest.getClinicalTest(catalogManagerResource, getVariantStorageEngine());
    }

    @Test
    public void tieringAnalysis() throws Exception {
        outDir = Paths.get(opencga.createTmpOutdir("_interpretation_analysis"));

        TieringInterpretationConfiguration config = new TieringInterpretationConfiguration();
        TieringInterpretationAnalysis tieringAnalysis = new TieringInterpretationAnalysis();
        tieringAnalysis.setUp(catalogManagerResource.getOpencgaHome().toString(), new ObjectMap(), outDir, clinicalTest.token);
        tieringAnalysis.setStudyId(clinicalTest.studyFqn)
                .setClinicalAnalysisId(clinicalTest.clinicalAnalysis.getId())
                .setPenetrance(ClinicalProperty.Penetrance.COMPLETE)
                .setConfig(config);

        ExecutionResult result = tieringAnalysis.start();

        System.out.println(result);

        checkInterpretation(0, result);
    }

    @Test
    public void teamAnalysis() throws IOException {
        outDir = Paths.get(opencga.createTmpOutdir("_interpretation_analysis"));

        try {
            TeamInterpretationConfiguration config = new TeamInterpretationConfiguration();
            TeamInterpretationAnalysis teamAnalysis = new TeamInterpretationAnalysis();
            teamAnalysis.setUp(catalogManagerResource.getOpencgaHome().toString(), new ObjectMap(), outDir, clinicalTest.token);
            teamAnalysis.setStudyId(clinicalTest.studyFqn)
                    .setClinicalAnalysisId(clinicalTest.clinicalAnalysis.getId())
                    .setConfig(config);

            teamAnalysis.start();

            fail();
        } catch (ToolException e) {
            Assert.assertEquals(e.getMessage(), "Missing disease panels for TEAM interpretation analysis");
        }
    }

    @Test
    public void customAnalysisFromClinicalAnalysisTest() throws Exception {
        outDir = Paths.get(opencga.createTmpOutdir("_interpretation_analysis"));

        //http://re-prod-opencgahadoop-tomcat-01.gel.zone:8080/opencga-test/webservices/rest/v1/analysis/clinical/interpretation/tools/custom?study=100k_genomes_grch38_germline%3ARD38&sid=eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJpbWVkaW5hIiwiYXVkIjoiT3BlbkNHQSB1c2VycyIsImlhdCI6MTU1MjY1NTYyNCwiZXhwIjoxNTUyNjU3NDI0fQ.6VO2mI_MJn3fejtdqdNi5W8uFa3rVXM2501QzN--Th8&sample=LP3000468-DNA_G06%3BLP3000473-DNA_C10%3BLP3000469-DNA_F03&summary=false&exclude=annotation.geneExpression&approximateCount=false&skipCount=true&useSearchIndex=auto&unknownGenotype=0%2F0&limit=10&skip=0
//        for (Variant variant : variantStorageManager.iterable(clinicalTest.token)) {
//            System.out.println("variant = " + variant.toStringSimple());// + ", ALL:maf = " + variant.getStudies().get(0).getStats("ALL").getMaf());
//        }
        Query query = new Query();
        query.put(VariantQueryParam.ANNOT_CONSEQUENCE_TYPE.key(), "missense_variant");

        ZettaInterpretationConfiguration config = new ZettaInterpretationConfiguration();
        ZettaInterpretationAnalysis customAnalysis = new ZettaInterpretationAnalysis();
        customAnalysis.setUp(catalogManagerResource.getOpencgaHome().toString(), new ObjectMap(), outDir, clinicalTest.token);
        customAnalysis.setStudyId(clinicalTest.studyFqn)
                .setClinicalAnalysisId(clinicalTest.clinicalAnalysis.getId())
                .setConfig(config);

        ExecutionResult result = customAnalysis.start();
        System.out.println(result);

        checkInterpretation(18, result);
    }

    @Test
    public void customAnalysisFromSamplesTest() throws Exception {
        outDir = Paths.get(opencga.createTmpOutdir("_interpretation_analysis"));

        //http://re-prod-opencgahadoop-tomcat-01.gel.zone:8080/opencga-test/webservices/rest/v1/analysis/clinical/interpretation/tools/custom?study=100k_genomes_grch38_germline%3ARD38&sid=eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJpbWVkaW5hIiwiYXVkIjoiT3BlbkNHQSB1c2VycyIsImlhdCI6MTU1MjY1NTYyNCwiZXhwIjoxNTUyNjU3NDI0fQ.6VO2mI_MJn3fejtdqdNi5W8uFa3rVXM2501QzN--Th8&sample=LP3000468-DNA_G06%3BLP3000473-DNA_C10%3BLP3000469-DNA_F03&summary=false&exclude=annotation.geneExpression&approximateCount=false&skipCount=true&useSearchIndex=auto&unknownGenotype=0%2F0&limit=10&skip=0
//        for (Variant variant : variantStorageManager.iterable(clinicalTest.token)) {
//            System.out.println("variant = " + variant.toStringSimple());// + ", ALL:maf = " + variant.getStudies().get(0).getStats("ALL").getMaf());
//        }
//        ObjectMap options = new ObjectMap();
//        String param = FamilyInterpretationAnalysis.SKIP_UNTIERED_VARIANTS_PARAM;
//        options.put(param, false);

        Query query = new Query();
//        List<String> samples = new ArrayList();
//        for (Individual member : clinicalTest.clinicalAnalysis.getFamily().getMembers()) {
//            if (CollectionUtils.isNotEmpty(member.getSamples())) {
//                samples.add(member.getSamples().get(0).getId());
//            }
//        }
//        query.put(VariantQueryParam.SAMPLE.key(), samples);
        query.put(VariantQueryParam.SAMPLE.key(), "s3");

        ZettaInterpretationConfiguration config = new ZettaInterpretationConfiguration();
        ZettaInterpretationAnalysis customAnalysis = new ZettaInterpretationAnalysis();
        customAnalysis.setUp(catalogManagerResource.getOpencgaHome().toString(), new ObjectMap(), outDir, clinicalTest.token);
        customAnalysis.setStudyId(clinicalTest.studyFqn)
                .setClinicalAnalysisId(clinicalTest.clinicalAnalysis.getId())
                .setQuery(query)
                .setConfig(config);

        ExecutionResult result = customAnalysis.start();

        System.out.println(result);

        checkInterpretation(12, result);
    }

    private void checkInterpretation(int expected, ExecutionResult result) {
        System.out.println("out dir (to absolute path) = " + outDir.toAbsolutePath());

        String msg = "Success";

        Interpretation interpretation = null;
        try {
            interpretation = readInterpretation(result, outDir);
            System.out.println("Interpreation ID: " + interpretation.getId());
            System.out.println("# primary findings: " + interpretation.getPrimaryFindings().size());
        } catch (ToolException e) {
            if (CollectionUtils.isNotEmpty(result.getEvents())) {
                System.out.println(StringUtils.join(result.getEvents(), "\n"));
            }
            Assert.fail();
        }

        Assert.assertEquals(expected, interpretation.getPrimaryFindings().size());
    }

    private Interpretation readInterpretation(ExecutionResult result, Path outDir)
            throws ToolException {
        File file = new java.io.File(outDir + "/" + InterpretationAnalysis.INTERPRETATION_FILENAME);
        if (file.exists()) {
            return ClinicalUtils.readInterpretation(file.toPath());
        }
        String msg = "Interpretation file not found for " + result.getId() + " analysis";
        if (CollectionUtils.isNotEmpty(result.getEvents())) {
            msg += (": " + StringUtils.join(result.getEvents(), ". "));
        }
        throw new ToolException(msg);
    }

}
