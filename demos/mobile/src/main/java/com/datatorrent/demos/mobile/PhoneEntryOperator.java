/**
 * Copyright (C) 2015 DataTorrent, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.datatorrent.demos.mobile;

import com.datatorrent.common.util.BaseOperator;
import com.datatorrent.api.DefaultInputPort;
import com.datatorrent.api.DefaultOutputPort;
import com.datatorrent.api.annotation.InputPortFieldAnnotation;
import com.google.common.collect.Maps;
import com.google.common.collect.Range;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.validation.constraints.Min;
import java.util.Map;
import java.util.Random;

/**
 * Generates mobile numbers that will be displayed in mobile demo just after launch.<br></br>
 * Operator attributes:<b>
 * <ul>
 *   <li>initialDisplayCount: No. of seed phone numbers that will be generated.</li>
 *   <li>maxSeedPhoneNumber: The largest seed phone number.</li>
 * </ul>
 * </b>
 *
 * @since 0.3.5
 */
public class PhoneEntryOperator extends BaseOperator
{
  private static Logger LOG = LoggerFactory.getLogger(PhoneEntryOperator.class);

  private boolean seedGenerationDone = false;

  @Min(0)
  private int initialDisplayCount = 0;

  private int maxSeedPhoneNumber = 0;
  private int rangeLowerEndpoint;
  private int rangeUpperEndpoint;

  /**
   * Sets the initial number of phones to display on the google map.
   * 
   * @param i the count of initial phone numbers to display 
   */
  public void setInitialDisplayCount(int i)
  {
    initialDisplayCount = i;
  }

  /**
   * Sets the range for the phone numbers generated by the operator.
   * 
   * @param i the range within which the phone numbers are randomly generated. 
   */
  public void setPhoneRange(Range<Integer> phoneRange)
  {
    this.rangeLowerEndpoint = phoneRange.lowerEndpoint();
    this.rangeUpperEndpoint = phoneRange.upperEndpoint();
  }

  /**
   * Sets the max seed for random phone number generation
   * 
   * @param i the number to initialize the random number phone generator.
   */
  public void setMaxSeedPhoneNumber(int number)
  {
    this.maxSeedPhoneNumber = number;
  }

  @InputPortFieldAnnotation(optional = true)
  public final transient DefaultInputPort<Map<String, String>> locationQuery = new DefaultInputPort<Map<String, String>>()
  {
    @Override
    public void process(Map<String, String> tuple)
    {
      seedPhones.emit(tuple);
    }
  };

  public final transient DefaultOutputPort<Map<String, String>> seedPhones = new DefaultOutputPort<Map<String, String>>();

  @Override
  public void beginWindow(long windowId){
    if (!seedGenerationDone) {
      Random random = new Random();
      int maxPhone = (maxSeedPhoneNumber <= rangeUpperEndpoint && maxSeedPhoneNumber >= rangeLowerEndpoint) ? maxSeedPhoneNumber : rangeUpperEndpoint;
      maxPhone -= 5550000;
      int phonesToDisplay = initialDisplayCount > maxPhone ? maxPhone : initialDisplayCount;
      for (int i = phonesToDisplay; i-- > 0; ) {
        int phoneNo = 5550000 + random.nextInt(maxPhone + 1);
        LOG.info("seed no: " + phoneNo);
        Map<String, String> valueMap = Maps.newHashMap();
        valueMap.put(PhoneMovementGenerator.KEY_COMMAND, PhoneMovementGenerator.COMMAND_ADD);
        valueMap.put(PhoneMovementGenerator.KEY_PHONE, Integer.toString(phoneNo));
        seedPhones.emit(valueMap);
      }
      // done generating data
      seedGenerationDone = true;
      LOG.info("Finished generating seed data.");
    }
  }
}
