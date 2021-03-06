/**
 * Licensed to Cloudera, Inc. under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  Cloudera, Inc. licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.cloudera.flume.handlers.debug;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Random;
import java.util.Set;

import org.junit.Before;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.cloudera.flume.core.Event;
import com.cloudera.flume.core.EventSink;
import com.cloudera.flume.core.connector.DirectDriver;
import com.cloudera.flume.reporter.ReportEvent;

/**
 * Demonstrates basic throttling works within some error-tolerance. There are
 * two different tests we perform here: IndividualChoke test and CollectiveChoke
 * test, their details are given with their respective test methods below.
 */
public class TestChokeDecos {
  public static final Logger LOG = LoggerFactory
      .getLogger(TestChokeDecos.class);

  Random rand = new Random(System.currentTimeMillis());

  final ChokeManager testChokeMan = new ChokeManager();
  final HashMap<String, Integer> chokeMap = new HashMap<String, Integer>();

  final long testTime = 5000; // in millisecs

  // number of drivers created for the testing
  final int numDrivers = 50;

  // here we set the limits for the minimum and maximum throttle rates in KB/sec
  int minTlimit = 500;
  int maxTlimit = 20000;
  // here we set the limits on size (in Bytes) of the messages passed
  int minMsgSize = 50;
  int maxMsgSize = 30000;

  /*
   * Error tolerance constants, these ratios are the max and min limits set on
   * the following quantity: MaxBytes allowed/Bytes Actually shipped. Note that
   * the error on the upperbound is much higher than the lower, this is because
   * it is possible that the driver threads don't get scheduled often enough to
   * ship bytes close to the maximum limits.
   */
  double highErrorLimit = 5;
  double lowErrorLimit = .5;

  @Before
  public void setup() {
    testChokeMan.setPayLoadHeaderSize(0);
  }

  /**
   * This extends the ChokeDecorator with the added functionality of
   * book-keeping the number of bytes shipped through it.
   */
  class TestChoke<S extends EventSink> extends ChokeDecorator<S> {

    public TestChoke(S s, String tId) {
      super(s, tId);
    }

    /*
     * We are overriding this because the method in ChokeManager calls
     * super.append() and we want to avoid this as the higher-level sink is not
     * initialized. In this method we just eliminate that call.
     */
    @Override
    public void append(Event e) throws IOException, InterruptedException {
      testChokeMan.spendTokens(chokeId, e.getBody().length);
      updateAppendStats(e);
    }
  }

  /**
   * The high level goal of this test is to see if many drivers using different
   * chokes can ship the data approximately at the max-limit set on them. In
   * more detail, in this test we create a bunch of FakeDrivers, and for each of
   * these drivers we assign them a unique choke. Then we run these Drivers and
   * check if the amount of data shipped accross each choke is approximately
   * what we expect.
   */
  @Test
  public void runInvidualChokeTest() throws InterruptedException, IOException {
    // number of chokes is equal to the number of drivers
    int numChokes = numDrivers;
    LOG.info("Setting up Individual Test");
    // create some chokeIDs with random limit in the range specified
    for (int i = 0; i < numChokes; i++) {
      // different chokesIds are created with their ids coming from the range
      // "1", "2", "3"...
      // with a throttlelimit in the range [minTlimit, maxTlimit]
      chokeMap.put(Integer.toString(i), minTlimit
          + rand.nextInt(maxTlimit - minTlimit));
    }
    // update the chokemap with these chokes
    testChokeMan.updateChokeLimitMap(chokeMap);
    // now we create bunch of chokes

    TestChoke[] tchokeArray = new TestChoke[numChokes];
    for (int i = 0; i < numChokes; i++) {
      // different chokes are created with their ids coming from the range "0",
      // "1", "2", "3"..."numChokes"
      tchokeArray[i] = new TestChoke<EventSink>(null, Integer.toString(i));
    }
    // one driver for each choke
    DirectDriver[] directDriverArray = new DirectDriver[numDrivers];

    for (int i = 0; i < numDrivers; i++) {
      // Driver i is mapped to ith choke, simple 1 to 1 mapping.
      directDriverArray[i] = new DirectDriver("TestDriver" + i,
          new SynthSourceRndSize(0, minMsgSize, maxMsgSize), tchokeArray[i]);
    }

    // check if all the ChokeIDs are present in the chokeMap
    LOG.info("Running the Individual Test Now!");
    for (int i = 0; i < numDrivers; i++) {
      if (!testChokeMan.isChokeId(Integer.toString(i))) {
        LOG.error("ChokeID " + Integer.toString(i) + "not present");
        fail("ChokeID " + Integer.toString(i) + "not present");
      }
    }
    // Now we start the test.
    // Start the ChokeManager.
    testChokeMan.start();
    for (DirectDriver d : directDriverArray) {
      d.start();
    }
    // stop for the allotted time period
    Thread.sleep(testTime);

    // Stop everything!
    for (DirectDriver d : directDriverArray) {
      d.stop();
    }
    testChokeMan.halt();
    // Now do the error evaluation, see how many bits were actually shipped.
    double errorRatio = 1.0;

    for (TestChoke<EventSink> t : tchokeArray) {
      // Now we compute the error ratio: Max/Actual.
      // Where Max= Maximum bytes which should have been shipped based on the
      // limit on this choke, and actual= bytes that were actually shipped.
      errorRatio = ((double) (chokeMap.get(t.getChokeId()) * testTime))
          / (double) (t.getReport().getLongMetric("number of bytes"));

      LOG.info("ChokeID: " + t.getChokeId() + ", error-ratio: " + errorRatio);
      ReportEvent r = t.getReport();

      LOG.info(" events :" + r.getLongMetric("number of events"));
      // Test if the error ratio is in the limit we want.
      assertFalse((errorRatio > this.highErrorLimit || errorRatio < this.lowErrorLimit));
    }
    LOG.info("Individual Test successful  !!!");
  }

  /**
   * The high level goal of this test is to make many driver threads contend
   * together on same chokes and see if they collectively ship the bytes under
   * the limits we want. We create just few chokes here, and for each Driver we
   * assign them one of these chokes at random.
   */

  @Test
  public void runCollectiveChokeTest() throws InterruptedException, IOException {
    // Few Chokes
    int numChokes = 5;
    LOG.info("Setting up Collective Test");
    // create chokeIDs with random limit range
    for (int i = 0; i < numChokes; i++) {
      // different chokesIds are created with their ids coming from the range
      // "0", "1", "2", "3"...
      // with a throttlelimit in the range [minTlimit, maxTlimit]
      chokeMap.put(Integer.toString(i), minTlimit
          + rand.nextInt(maxTlimit - minTlimit));
    }
    // update the chokemap with these chokes
    testChokeMan.updateChokeLimitMap(chokeMap);
    // Initialize the chokes appropriately.
    TestChoke[] tchokeArray = new TestChoke[numChokes];
    for (int i = 0; i < numChokes; i++) {
      // different chokes are created with their ids coming from the range "0",
      // "1", "2", "3"..."numFakeDrivers"
      tchokeArray[i] = new TestChoke<EventSink>(null, Integer.toString(i));
    }

    // As we are assigning the chokes to drivers at random, there is a chance
    // that not all initialized chokes are assigned to some driver. So the
    // number of bytes shipped on these chokes will be zero, which will throw us
    // off in the error evaluation. For this reason we add all the chokes
    // assigned to some driver in a set, and do error evaluation only on those
    // chokes.

    // chokesUsed is the set of chokes assigned to some driver.
    Set<TestChoke<EventSink>> chokesUsed = new HashSet<TestChoke<EventSink>>();

    DirectDriver[] directDriverArray = new DirectDriver[numDrivers];
    // Each driver is randomly assigned to a random choke in the range
    // [0,numChokes)
    int randChokeIndex = 0;
    for (int i = 0; i < numDrivers; i++) {
      randChokeIndex = rand.nextInt(numChokes);
      // DirectDriverArray[i] = new DirectDriver(new SynthSourceRndSize(0,
      // minMsgSize, maxMsgSize), tchokeArray[randChokeIndex]);
      directDriverArray[i] = new DirectDriver(new SynthSourceRndSize(0,
          minMsgSize, maxMsgSize), tchokeArray[randChokeIndex]);

      // adds this choke to the set of chokesUsed
      chokesUsed.add(tchokeArray[randChokeIndex]);
    }
    // check if all the ChokeIDs are present
    LOG.info("Running the Collective Test Now!");
    for (TestChoke<EventSink> t : chokesUsed) {
      if (!testChokeMan.isChokeId(t.getChokeId())) {
        LOG.error("ChokeID " + t.getChokeId() + "not present");
        fail();
      }
    }

    // Now we start the test.
    // start the ChokeManager
    testChokeMan.start();
    for (DirectDriver f : directDriverArray) {
      f.start();
    }
    // stop for the allotted time period
    Thread.sleep(testTime);
    // Stop everything!
    for (DirectDriver f : directDriverArray) {
      f.stop();
    }
    testChokeMan.halt();
    // now do the error evaluation
    double errorRatio = 1.0;

    for (TestChoke<EventSink> t : chokesUsed) {
      // Now we compute the error ratio: Max/Actual.
      // Where Max= Maximum bytes which should have been shipped based on the
      // limit on this choke, and actual= bytes that were actually shipped.
      errorRatio = ((double) (chokeMap.get(t.getChokeId()) * testTime))
          / (double) (t.getReport().getLongMetric("number of bytes"));

      LOG.info("ChokeID: " + t.getChokeId() + ", error-ratio: " + errorRatio);
      // Test if the error ratio is in the limit we want.
      assertFalse((errorRatio > this.highErrorLimit || errorRatio < this.lowErrorLimit));
    }
    LOG.info("Collective test successful  !!!");
  }
}
