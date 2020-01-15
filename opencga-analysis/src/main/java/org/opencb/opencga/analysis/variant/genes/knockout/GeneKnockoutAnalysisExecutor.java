package org.opencb.opencga.analysis.variant.genes.knockout;

import com.fasterxml.jackson.databind.ObjectWriter;
import org.opencb.opencga.analysis.variant.genes.knockout.result.GeneKnockoutByGene;
import org.opencb.opencga.analysis.variant.genes.knockout.result.GeneKnockoutBySample;
import org.opencb.opencga.analysis.variant.genes.knockout.result.GeneKnockoutBySample.GeneKnockout;
import org.opencb.opencga.analysis.variant.genes.knockout.result.VariantKnockout;
import org.opencb.opencga.core.common.JacksonUtils;
import org.opencb.opencga.core.models.sample.Sample;
import org.opencb.opencga.core.tools.OpenCgaToolExecutor;
import org.opencb.opencga.storage.core.metadata.models.Trio;
import org.opencb.opencga.storage.core.variant.adaptors.VariantQueryUtils;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;

public abstract class GeneKnockoutAnalysisExecutor extends OpenCgaToolExecutor {

    private String study;
    private List<String> samples;
    private Map<String, Trio> trios;

    private Set<String> proteinCodingGenes;
    private Set<String> otherGenes;
    private String ct;
    private Set<String> cts;
    private String filter;
    private String qual;

    private String disorder;
    private String sampleFileNamePattern;
    private String geneFileNamePattern;
    private String biotype;

    public String getStudy() {
        return study;
    }

    public GeneKnockoutAnalysisExecutor setStudy(String study) {
        this.study = study;
        return this;
    }

    public List<String> getSamples() {
        return samples;
    }

    public GeneKnockoutAnalysisExecutor setSamples(List<String> samples) {
        this.samples = samples;
        return this;
    }

    public Map<String, Trio> getTrios() {
        return trios;
    }

    public GeneKnockoutAnalysisExecutor setTrios(Map<String, Trio> trios) {
        this.trios = trios;
        return this;
    }

    public Set<String> getProteinCodingGenes() {
        return proteinCodingGenes;
    }

    public GeneKnockoutAnalysisExecutor setProteinCodingGenes(Set<String> proteinCodingGenes) {
        this.proteinCodingGenes = proteinCodingGenes;
        return this;
    }

    public Set<String> getOtherGenes() {
        return otherGenes;
    }

    public GeneKnockoutAnalysisExecutor setOtherGenes(Set<String> otherGenes) {
        this.otherGenes = otherGenes;
        return this;
    }

    public GeneKnockoutAnalysisExecutor setBiotype(String biotype) {
        this.biotype = biotype;
        return this;
    }

    public String getBiotype() {
        return biotype;
    }

    public String getCt() {
        return ct;
    }

    public GeneKnockoutAnalysisExecutor setCt(String ct) {
        this.ct = ct;
        if (ct != null && !ct.isEmpty()) {
            cts = new HashSet<>(VariantQueryUtils.parseConsequenceTypes(Arrays.asList(ct.split(","))));
        } else {
            cts = Collections.emptySet();
        }
        return this;
    }

    public Set<String> getCts() {
        return cts;
    }

    public String getFilter() {
        return filter;
    }

    public GeneKnockoutAnalysisExecutor setFilter(String filter) {
        this.filter = filter;
        return this;
    }

    public String getQual() {
        return qual;
    }

    public GeneKnockoutAnalysisExecutor setQual(String qual) {
        this.qual = qual;
        return this;
    }

    public String getDisorder() {
        return disorder;
    }

    public GeneKnockoutAnalysisExecutor setDisorder(String disorder) {
        this.disorder = disorder;
        return this;
    }

    public String getSampleFileNamePattern() {
        return sampleFileNamePattern;
    }

    public GeneKnockoutAnalysisExecutor setSampleFileNamePattern(String sampleFileNamePattern) {
        this.sampleFileNamePattern = sampleFileNamePattern;
        return this;
    }

    public Path getSampleFileName(String sample) {
        return Paths.get(sampleFileNamePattern.replace("{sample}", sample));
    }

    public String getGeneFileNamePattern() {
        return geneFileNamePattern;
    }

    public GeneKnockoutAnalysisExecutor setGeneFileNamePattern(String geneFileNamePattern) {
        this.geneFileNamePattern = geneFileNamePattern;
        return this;
    }

    protected Path getGeneFileName(String gene) {
        return Paths.get(geneFileNamePattern.replace("{gene}", gene));
    }

    protected GeneKnockoutBySample buildGeneKnockoutBySample(String sample, Map<String, GeneKnockout> knockoutGenes) {
        GeneKnockoutBySample.GeneKnockoutBySampleStats stats = new GeneKnockoutBySample.GeneKnockoutBySampleStats()
                .setNumGenes(knockoutGenes.size())
                .setNumTranscripts(knockoutGenes.values().stream().mapToInt(g -> g.getTranscripts().size()).sum());
        for (VariantKnockout.KnockoutType type : VariantKnockout.KnockoutType.values()) {
            long count = knockoutGenes.values().stream().flatMap(g -> g.getTranscripts().stream())
                    .flatMap(t -> t.getVariants().stream())
                    .filter(v -> v.getKnockoutType().equals(type))
                    .map(VariantKnockout::getVariant)
                    .collect(Collectors.toSet())
                    .size();
            stats.getByType().put(type, count);
        }

        return new GeneKnockoutBySample()
                .setSample(new Sample().setId(sample))
                .setStats(stats)
                .setGenes(knockoutGenes.values());
    }

    protected void writeSampleFile(GeneKnockoutBySample geneKnockoutBySample) throws IOException {
        File file = getSampleFileName(geneKnockoutBySample.getSample().getId()).toFile();
        ObjectWriter writer = JacksonUtils.getDefaultObjectMapper().writerFor(GeneKnockoutBySample.class).withDefaultPrettyPrinter();
        writer.writeValue(file, geneKnockoutBySample);
    }

    protected GeneKnockoutBySample readSampleFile(String sample) throws IOException {
        File file = getSampleFileName(sample).toFile();
        return JacksonUtils.getDefaultObjectMapper().readValue(file, GeneKnockoutBySample.class);
    }

    protected void writeGeneFile(GeneKnockoutByGene geneKnockoutByGene) throws IOException {
        File file = getGeneFileName(geneKnockoutByGene.getName()).toFile();
        ObjectWriter writer = JacksonUtils.getDefaultObjectMapper().writerFor(GeneKnockoutByGene.class).withDefaultPrettyPrinter();
        writer.writeValue(file, geneKnockoutByGene);
    }

    protected GeneKnockoutByGene readGeneFile(String gene) throws IOException {
        File file = getGeneFileName(gene).toFile();
        return JacksonUtils.getDefaultObjectMapper().readValue(file, GeneKnockoutByGene.class);
    }
}
