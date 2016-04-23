package joshua.decoder;

import static java.util.Arrays.asList;
import static java.util.Collections.emptyList;
import static java.util.Collections.emptyMap;
import static joshua.decoder.hypergraph.ViterbiExtractor.walk;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import joshua.decoder.ff.FeatureFunction;
import joshua.decoder.ff.FeatureVector;
import joshua.decoder.hypergraph.HyperGraph;
import joshua.decoder.hypergraph.KBestExtractor.DerivationState;
import joshua.decoder.io.DeNormalize;
import joshua.decoder.hypergraph.ViterbiFeatureVectorWalkerFunction;
import joshua.decoder.hypergraph.ViterbiOutputStringWalkerFunction;
import joshua.decoder.hypergraph.WalkerFunction;
import joshua.decoder.hypergraph.WordAlignmentExtractor;
import joshua.decoder.segment_file.Sentence;

/**
 * structuredTranslation provides a more structured access to translation
 * results than the Translation class.
 * Members of instances of this class can be used upstream.
 * <br/>
 * TODO:
 * Enable K-Best extraction.
 * 
 * @author fhieber
 */
public class StructuredTranslation {
  
  private final Sentence sourceSentence;
  private final DerivationState derivationRoot;
  private final JoshuaConfiguration joshuaConfiguration;
  
  private String translationString = null;
  private List<String> translationTokens = null;
  private String translationWordAlignments = null;
  private FeatureVector translationFeatures = null;
  private float extractionTime = 0.0f;
  private float translationScore = 0.0f;
  
  /* If we need to replay the features, this will get set to true, so that it's only done once */
  private boolean featuresReplayed = false;

  public StructuredTranslation(final Sentence sourceSentence,
      final DerivationState derivationRoot,
      JoshuaConfiguration config) {

    this(sourceSentence, derivationRoot, config, true);
  }

  
  public StructuredTranslation(final Sentence sourceSentence,
      final DerivationState derivationRoot,
      JoshuaConfiguration config,
      boolean now) {

    final long startTime = System.currentTimeMillis();

    this.sourceSentence = sourceSentence;
    this.derivationRoot = derivationRoot;
    this.joshuaConfiguration = config;

    if (now) {
      getTranslationString();
      getTranslationTokens();
      getTranslationScore();
      getTranslationFeatures();
      getTranslationWordAlignments();
    }
    this.translationScore = getTranslationScore();

    this.extractionTime = (System.currentTimeMillis() - startTime) / 1000.0f;
  }
  

  // Getters to use upstream
  
  public Sentence getSourceSentence() {
    return sourceSentence;
  }

  public int getSentenceId() {
    return sourceSentence.id();
  }

  public String getTranslationString() {
    if (this.translationString == null) {
      if (derivationRoot == null) {
        this.translationString = sourceSentence.source();
      } else {
        this.translationString = derivationRoot.getHypothesis();
      }
    }
    return this.translationString;
  }

  public List<String> getTranslationTokens() {
    if (this.translationTokens == null) {
      String trans = getTranslationString();
      if (trans.isEmpty()) {
        this.translationTokens = emptyList();
      } else {
        this.translationTokens = asList(trans.split("\\s+"));
      }
    }
    
    return translationTokens;
  }

  public float getTranslationScore() {
    if (derivationRoot == null) {
      this.translationScore = 0.0f;
    } else {
      this.translationScore = derivationRoot.getModelCost();
    }
    
    return translationScore;
  }

  /**
   * Returns a list of target to source alignments.
   */
  public String getTranslationWordAlignments() {
    if (this.translationWordAlignments == null) {
      if (derivationRoot == null)
        this.translationWordAlignments = "";
      else {
        WordAlignmentExtractor wordAlignmentExtractor = new WordAlignmentExtractor();
        derivationRoot.visit(wordAlignmentExtractor);
        this.translationWordAlignments = wordAlignmentExtractor.toString();
      }
    }

    return this.translationWordAlignments;
  }
  
  public FeatureVector getTranslationFeatures() {
    if (this.translationFeatures == null)
      this.translationFeatures = derivationRoot.replayFeatures();

    return translationFeatures;
  }
  
  /**
   * Time taken to build output information from the hypergraph.
   */
  public Float getExtractionTime() {
    return extractionTime;
  }
}
