/**
 *   CartsBusBoarding - Bus Boarding Event detection project by
 *                      CARTS in IITB & UIET, Panjab University
 *
 *   Copyright (c) 2014 Shubham Chaudhary <me@shubhamchaudhary.in>
 *   Copyright (c) 2014 Tanjot Kaur <tanjot28@gmail.com>
 *
 *   This file is part of CartsBusBoarding.
 *
 *   CartsBusBoarding is free software: you can redistribute it and/or modify
 *   it under the terms of the GNU General Public License as published by
 *   the Free Software Foundation, either version 3 of the License, or
 *   (at your option) any later version.
 *
 *   CartsBusBoarding is distributed in the hope that it will be useful,
 *   but WITHOUT ANY WARRANTY; without even the implied warranty of
 *   MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *   GNU General Public License for more details.
 *
 *   You should have received a copy of the GNU General Public License
 *   along with CartsBusBoarding.  If not, see <http://www.gnu.org/licenses/>.
 */

package in.ac.iitb.cse.cartsbusboarding;

import android.content.Context;
import in.ac.iitb.cse.cartsbusboarding.acc.AccEngine;
import in.ac.iitb.cse.cartsbusboarding.acc.FeatureCalculator;
import in.ac.iitb.cse.cartsbusboarding.utils.LogUtils;
import libsvm.svm_model;
import libsvm.svm_node;
import libsvm.svm_parameter;
import libsvm.svm_problem;

import javax.inject.Inject;
import java.io.*;
import java.util.HashMap;
import java.util.HashSet;

import static in.ac.iitb.cse.cartsbusboarding.utils.LogUtils.*;
import static libsvm.svm.svm_predict;
import static libsvm.svm.svm_train;

/**
 * Applies machine learning
 */
public class Machine {
    private static final String TAG = LogUtils.makeLogTag(Machine.class);
    public static final String TRAINING_MODEL_FILE = "cbb_training_model";
    private static svm_model mModelInstance = null;
    @Inject AccEngine mAccEngine;
    @Inject Context mContext;

    /**
     *
     * @param accEngine needed to get the features
     */
    @Inject
    public Machine(AccEngine accEngine, Context context) {
        mAccEngine = accEngine;
        mContext = context;
        if (mModelInstance == null)
            mModelInstance = getTrainedModel();
    }

    public svm_model getTrainedModel() {
        svm_model model = readModel();
        if (model == null) {
            model = trainMachine();
            writeModel(model);
        }
        return model;
    }

    private svm_model readModel() {
        svm_model model = null;
        try {
            InputStream stream = mContext.openFileInput(TRAINING_MODEL_FILE);
            InputStream buffer = new BufferedInputStream(stream);
            ObjectInput input = new ObjectInputStream(buffer);
            try {
                model = (svm_model) input.readObject();
            } finally {
                input.close();
            }
            LOGI(TAG, "Read training model from file: " + TRAINING_MODEL_FILE);
        } catch (ClassNotFoundException e) {
            LOGE(TAG, "Unable to convert input object to svm_model");
            e.printStackTrace();
        } catch (FileNotFoundException e) {
            LOGE(TAG, "Training model file not found");
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        }
        return model;
    }

    private void writeModel(svm_model model) {
        try {
            FileOutputStream fileOutputStream = mContext.openFileOutput(TRAINING_MODEL_FILE, Context.MODE_PRIVATE);
            OutputStream outputStream = new BufferedOutputStream(fileOutputStream);
            ObjectOutputStream objectOutputStream = new ObjectOutputStream(outputStream);
            try {
                objectOutputStream.writeObject(model);
            } finally {
                objectOutputStream.close();
            }
            LOGI(TAG, "Wrote training model to file: " + TRAINING_MODEL_FILE);
        } catch (FileNotFoundException e) {
            LOGE(TAG, "Training model file not found");
            e.printStackTrace();
        } catch (IOException e) {
            LOGE(TAG, "Unable to save training model");
            e.printStackTrace();
        }
    }

    /**
     * Checks if staircase pattern found in acc buffer data
     *
     * @return true if detected at least one staircase pattern
     */
    public boolean foundStairPattern() {
        double[] idx = testMachine();
        //Match if all values are equal
        double old = idx[0];
        for (double idxVal : idx) {
            if (idxVal != old)
                return true;
        }
        return false;
    }

    /**
     * @return the average idx values
     */
    public double getAvgIdx() {
        double[] idx = testMachine();
        double avg = 0;
        int count1 = 0, count2 = 0;
        for (double idxVal : idx) {
            if (idxVal == 1.0) count1++;
            if (idxVal == 2.0) count2++;
            avg += idxVal;
        }
        avg /= idx.length;
        LOGI(TAG, "Avg IDX: " + avg + " with " + count1 + " 1s & " + count2 + " 2s");
        return avg;
    }

    /**
     * Set the parameter values for training
     * @return
     */
    private svm_parameter getParameters() {
        svm_parameter parameter = new svm_parameter();
        parameter.svm_type = svm_parameter.C_SVC;
        parameter.kernel_type = svm_parameter.RBF; //XXX: Select right kernel type
        parameter.degree = 3;
        parameter.gamma = 0.25;
        parameter.nu = 0.5;
        parameter.cache_size = 100;
        parameter.C = 1;
        parameter.eps = 1e-3;
        parameter.p = 0.1;
        parameter.shrinking = 1;
        parameter.probability = 0;
        parameter.nr_weight = 0;
        parameter.weight_label = null;
        parameter.weight = null;
        return parameter;
    }

    /**
     * train data is prepared from the file passed and trained using svm machine
     * @return svm_model after training the data
     */
    private svm_model trainMachine() {
        svm_parameter parameter = getParameters();

        //TODO: pass train filename
        try {
            InputStream istream = mContext.getAssets().open("train_data_expert.train");
            MyReadData featureData = readData(new BufferedReader(new InputStreamReader(istream)));

            //Train the SVM model
            svm_problem prob = new svm_problem();
            int numTrainingInstances = featureData.featuresData.keySet().size();
            prob.l = numTrainingInstances;
            prob.y = new double[prob.l];
            prob.x = new svm_node[prob.l][];

            for (int i = 0; i < numTrainingInstances; i++) {
                HashMap<Integer, Double> tmp = featureData.featuresData.get(i);

                prob.x[i] = new svm_node[tmp.keySet().size()];
                int index = 0;
                for (Integer id : tmp.keySet()) {
                    svm_node node = new svm_node();
                    node.index = id;
                    node.value = tmp.get(id);
//                    Log.e("train index:value",node.index+":"+node.value);
                    prob.x[i][index] = node;
                    index++;
                }
                //Log.e("train idx",""+featureData.label.get(i));
                prob.y[i] = featureData.label.get(i);
            }

            svm_model model = svm_train(prob, parameter);
            LOGW(TAG, "Model: " + model.toString());
            return model;
        } catch (IOException e) {
            e.printStackTrace();
        }
        return null;
    }

    /**
     * Prepares test data by applying features on windows of data in buffer
     * @return string of features applied on data in windows
     */
    private String getTestData() {
        FeatureCalculator mFeatureCalculator = new FeatureCalculator(mAccEngine);
        int windowSize = 20;
        double[][] features = new double[][]{
                mFeatureCalculator.getMean(windowSize),
                mFeatureCalculator.getStd(windowSize),
                mFeatureCalculator.getDCComponent(windowSize),
                mFeatureCalculator.getEnergy(windowSize)
        };

        String output_strings = "";
        int noOfReadings = features[0].length;
        for (int colIndex = 0; colIndex < noOfReadings; colIndex++) {
            output_strings += "1 ";     //To make it look like train file
            for (int rowIndex = 0; rowIndex < features.length; rowIndex++) {
                double feature_value = features[rowIndex][colIndex];
                output_strings += (rowIndex + 1) + ":" + feature_value + " ";
            }
            LOGV(TAG, output_strings);
            output_strings += "\n";
        }
        //XXX: This is just one line!
        return output_strings;
    }

    /**
     * Train and test the machine with mainBuffer data
     * @return idx values predicted
     */
    double[] testMachine() {
        //Train machine should be called only once

        //creating test data from string returned by getTestData
        MyReadData data = readData(new BufferedReader(new StringReader(getTestData())));

        int dataSize = data.featuresData.size();
        double[] idx = new double[dataSize];
        String print_idx = "";
        for (int i = 0; i < dataSize; ++i) {
            HashMap<Integer, Double> tmp = data.featuresData.get(i);
            int numFeatures = tmp.keySet().size();
            svm_node[] x = new svm_node[numFeatures];
            int featureIndex = 0;
            for (Integer feature : tmp.keySet()) {
                x[featureIndex] = new svm_node();
                x[featureIndex].index = feature;
//                LOGV(TAG+" feature value",""+tmp.get(feature));
                x[featureIndex].value = tmp.get(feature);
                //LOGE("train index:value",x[featureIndex].index+":"+x[featureIndex].value);

                featureIndex++;
            }
            //LOGE("train idx",""+data.label.get(i));

            idx[i] = svm_predict(mModelInstance, x);
            print_idx += idx[i] + " ";
        }

        LOGI(TAG, "Prediction: " + print_idx);
        return idx;
    }

    /**
     * Separates features and label into different Hash Maps(both in class structure MyReadData)
     * @param reader from where data is to be obtained
     * @return separated data as class structure MyReadData
     */
    private MyReadData readData(BufferedReader reader) {
        HashSet<Integer> features = new HashSet<Integer>();
        MyReadData data = new MyReadData();


        try {
            String line = null;
            int lineNum = 0;
            while ((line = reader.readLine()) != null) {
                data.featuresData.put(lineNum, new HashMap<Integer, Double>());
                String[] tokens = line.split("\\s+");
                int labelToken = Integer.parseInt(tokens[0]);
                data.label.put(lineNum, labelToken);
                for (int i = 1; i < tokens.length; i++) {
                    String[] fields = tokens[i].split(":");
                    int featureId = Integer.parseInt(fields[0]);
                    double featureValue = Double.parseDouble(fields[1]);
                    features.add(featureId);
                    data.featuresData.get(lineNum).put(featureId, featureValue);
                }
                lineNum++;
            }

            reader.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
        return data;
    }

    /**
     * Structure to hold features and labels in separate Hash Maps
     */
    class MyReadData {
        HashMap<Integer, HashMap<Integer, Double>> featuresData;
        HashMap<Integer, Integer> label;

        MyReadData() {
            featuresData = new HashMap<Integer, HashMap<Integer, Double>>();
            label = new HashMap<Integer, Integer>();
        }
    }
}
