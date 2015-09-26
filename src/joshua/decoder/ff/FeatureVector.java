package joshua.decoder.ff;

import joshua.decoder.Decoder;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * An implementation of a sparse feature vector, using for representing both weights and feature
 * values.
 * 
 * @author Matt Post <post@cs.jhu.edu>
 */

public class FeatureVector {
  private ArrayList<String> denseFeatureNames = null;
  private ArrayList<Float> denseFeatures = null;
  private HashMap<String, Float> features;

  public FeatureVector() {
    features = new HashMap<String, Float>();
    denseFeatureNames = new ArrayList<String>();
    denseFeatures = new ArrayList<Float>();
  }

  public FeatureVector(String feature, float value) {
    features = new HashMap<String, Float>();
    features.put(feature, value);
    denseFeatureNames = new ArrayList<String>();
    denseFeatures = new ArrayList<Float>();
  }

  /**
   * This version of the constructor takes an uninitialized feature with potentially intermingled
   * labeled and unlabeled feature values, of the format:
   * 
   * [feature1=]value [feature2=]value
   * 
   * It produces a Feature Vector where all unlabeled features have been labeled by appending the
   * unlabeled feature index (starting at 0) to the defaultPrefix value.
   * 
   * **IMPORTANT** The feature values are inverted, for historical reasons, which leads to a lot
   * of confusion. They have to be inverted here and when the score is actually computed. They 
   * are inverted here (which is used to build the feature vector representation of a rule's dense
   * features) and in {@link BilingualRule::estimateRuleCost()}, where the rule's precomputable
   * (weighted) score is cached.
   * 
   * @param featureString, the string of labeled and unlabeled features (probably straight from the
   *          grammar text file)
   * @param prefix, the prefix to use for unlabeled features (probably "tm_OWNER_")
   */
  public FeatureVector(String featureString, String prefix) {

    /*
     * Read through the features on this rule, adding them to the feature vector. Unlabeled features
     * are converted to a canonical form.
     * 
     * Note that it's bad form to mix unlabeled features and the named feature index they are mapped
     * to, but we are being liberal in what we accept.
     * 
     * IMPORTANT: Note that, for historical reasons, the sign is reversed on all scores.
     * This is the source of *no end* of confusion and should be done away with.
     */
    features = new HashMap<String, Float>();
    int denseFeatureIndex = 0;

    if (!featureString.trim().equals("")) {
      for (String token : featureString.split("\\s+")) {
        if (token.indexOf('=') == -1) {
          features.put(String.format("%s%d", prefix, denseFeatureIndex), -Float.parseFloat(token));
          denseFeatureIndex++;
        } else {
          int splitPoint = token.indexOf('=');
          features.put(token.substring(0, splitPoint),
              Float.parseFloat(token.substring(splitPoint + 1)));
        }
      }
    }
  }
  
  /**
   * Register one or more dense features with the global weight vector. This assumes them global
   * IDs, and then returns the index of the first feature (from which the calling feature function
   * can infer them all). This *must* be called by every feature function wishing to register
   * dense features!
   * 
   * @param names
   * @return
   */
  public int registerDenseFeatures(ArrayList<String> names) {
    for (String name: names)
      registerDenseFeature(name);

    // this is the index of the first feature added
    return denseFeatureNames.size() - names.size();
  }
  
  public int registerDenseFeature(String name) {
    denseFeatureNames.add(name);
    denseFeatures.add(features.get(name));
    Decoder.LOG(2, String.format("Assigning index %d to feature %s", denseFeatureNames.size() - 1, name));
    return denseFeatureNames.size() - 1;
  }
  
  public ArrayList<String> getDenseFeatures() {
    return denseFeatureNames;
  }

  public Set<String> keySet() {
    return features.keySet();
  }

  public int size() {
    return features.size();
  }

  public FeatureVector clone() {
    FeatureVector newOne = new FeatureVector();
    for (String key : this.features.keySet())
      newOne.set(key, this.features.get(key));
    return newOne;
  }

  /**
   * Subtracts the weights in the other feature vector from this one. Note that this is not set
   * subtraction; keys found in the other FeatureVector but not in this one will be initialized with
   * a value of 0.0f before subtraction.
   */
  public void subtract(FeatureVector other) {
    for (String key : other.keySet()) {
      float oldValue = (features.containsKey(key)) ? features.get(key) : 0.0f;
      features.put(key, oldValue - other.get(key));
    }
  }

  /**
   * Adds the weights in the other feature vector to this one. This is set union, with values shared
   * between the two being summed.
   */
  public void add(FeatureVector other) {
    for (String key : other.keySet()) {
      if (!features.containsKey(key))
        features.put(key, other.get(key));
      else
        features.put(key, features.get(key) + other.get(key));
    }
  }

  public boolean containsKey(final String feature) {
    return features.containsKey(feature);
  }

  /**
   * This method returns the weight of a feature if it exists and otherwise throws a runtime error.
   * It is the duty of the programmer to check using the method containsKey if a feature with a
   * certain name exists. Previously this method would return 0 if the key did not exists, but this
   * lead to bugs in other parts of the code because Feature Names are often specified in capitals
   * but then lowercased, but in using the get method the lowercase form is not used consistently.
   * It is therefore good defensive programming to just throw an error when someone tries to get a
   * feature that does not exist - this will automatically eliminate such hard to debug errors. This
   * is what is now implemented.
   * 
   * @param feature
   * @return
   */
  public float get(String feature) {
    if (features.containsKey(feature))
      return features.get(feature);

    return 0.0f;
  }
  
  public float get(int id) {
    return denseFeatures.get(id);
  }

  public void increment(String feature, float value) {
    if (features.containsKey(feature))
      features.put(feature, features.get(feature) + value);
    else
      features.put(feature, value);
  }
  
  public void increment(int id, float value) {
    while (denseFeatures.size() <= id)
      denseFeatures.add(0.0f);
    denseFeatures.set(id, denseFeatures.get(id) + value);
  }
  
  public void set(String feature, float value) {
    features.put(feature, value);
  }
  
  public void set(int id, float value) {
    while (denseFeatures.size() <= id)
      denseFeatures.add(0.0f);
    denseFeatures.set(id, value);
  }

  public Map<String, Float> getMap() {
    return features;
  }

  /**
   * Computes the inner product between this feature vector and another one.
   */
  public float innerProduct(FeatureVector other) {
    float cost = 0.0f;
    for (String key : features.keySet())
      if (other.containsKey(key))
        cost += features.get(key) * other.get(key);

    return cost;
  }

  public void times(float value) {
    for (String key : features.keySet())
      features.put(key, features.get(key) * value);
  }

  /***
   * Moses distinguishes sparse features as those containing an underscore, so we have to fake it
   * to be compatible with their tuners.
   */
  public String mosesString() {
    String outputString = "";
    
    HashSet<String> printed_keys = new HashSet<String>();
    
    // First print all the dense feature names in order
    for (int i = 0; i < denseFeatureNames.size(); i++) {
      outputString += String.format("%s=%.3f ", denseFeatureNames.get(i).replaceAll("_", "-"), denseFeatures.get(i));
      printed_keys.add(denseFeatureNames.get(i));
    }
    
    // Now print the sparse features
    ArrayList<String> keys = new ArrayList<String>(features.keySet());
    Collections.sort(keys);
    for (String key: keys) {
      if (! printed_keys.contains(key)) {
        float value = features.get(key);
        if (key.equals("OOVPenalty"))
          // force moses to see it as sparse
          key = "OOV_Penalty";
        outputString += String.format("%s=%.3f ", key, value);
      }
    }
    return outputString.trim();
  }
    
  /***
   * Outputs a list of feature names. All dense features are printed. Feature names are printed
   * in the order they were read in.
   */
  @Override
  public String toString() {
    String outputString = "";
    
    HashSet<String> printed_keys = new HashSet<String>();
    
    // First print all the dense feature names in order
    for (int i = 0; i < denseFeatureNames.size(); i++) {
      outputString += String.format("%s=%.3f ", denseFeatureNames.get(i), denseFeatures.get(i));
      printed_keys.add(denseFeatureNames.get(i));
    }
    
    // Now print the rest of the features
    ArrayList<String> keys = new ArrayList<String>(features.keySet());
    Collections.sort(keys);
    for (String key: keys)
      if (! printed_keys.contains(key))
        outputString += String.format("%s=%.3f ", key, features.get(key));

    return outputString.trim();
  }

  public boolean isDense(String feature) {
    for (String candidate: denseFeatureNames)
      if (feature.equals(candidate))
        return true;

    return false;
  }
}
