/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package com.datatorrent.lib.io.fs;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeoutException;

import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestWatcher;
import org.junit.runner.Description;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.apex.malhar.lib.wal.FSWindowDataManager;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.hadoop.fs.FileContext;
import org.apache.hadoop.fs.Path;

import com.google.common.collect.Sets;
import com.datatorrent.api.Attribute;
import com.datatorrent.api.Context;
import com.datatorrent.lib.helper.OperatorContextTestHelper;
import com.datatorrent.lib.io.block.BlockMetadata;
import com.datatorrent.lib.testbench.CollectorTestSink;
import com.datatorrent.lib.util.KryoCloneUtils;
import com.datatorrent.lib.util.TestUtils;

/**
 * Tests for {@link FileSplitterInput}
 */
public class FileSplitterInputTest
{
  static Set<String> createData(String dataDirectory) throws IOException
  {
    Set<String> filePaths = Sets.newHashSet();
    FileContext.getLocalFSFileContext().delete(new Path(new File(dataDirectory).getAbsolutePath()), true);
    HashSet<String> allLines = Sets.newHashSet();
    for (int file = 0; file < 12; file++) {
      HashSet<String> lines = Sets.newHashSet();
      for (int line = 0; line < 2; line++) {
        lines.add("f" + file + "l" + line);
      }
      allLines.addAll(lines);
      File created = new File(dataDirectory, "file" + file + ".txt");
      filePaths.add(created.getAbsolutePath());
      FileUtils.write(created, StringUtils.join(lines, '\n'));
    }
    return filePaths;
  }

  public static class TestMeta extends TestWatcher
  {
    String dataDirectory;

    FileSplitterInput fileSplitterInput;
    CollectorTestSink<FileSplitterInput.FileMetadata> fileMetadataSink;
    CollectorTestSink<BlockMetadata.FileBlockMetadata> blockMetadataSink;
    Set<String> filePaths;
    Context.OperatorContext context;
    MockScanner scanner;

    @Override
    protected void starting(org.junit.runner.Description description)
    {
      TestUtils.deleteTargetTestClassFolder(description);
      String methodName = description.getMethodName();
      String className = description.getClassName();
      this.dataDirectory = "target/" + className + "/" + methodName + "/data";
      try {
        filePaths = createData(this.dataDirectory);
      } catch (IOException e) {
        throw new RuntimeException(e);
      }

      fileSplitterInput = new FileSplitterInput();
      fileSplitterInput.setBlocksThreshold(100);
      scanner = new MockScanner();
      scanner.setScanIntervalMillis(500);
      scanner.setFilePatternRegularExp(".*[.]txt");
      scanner.setFiles(dataDirectory);
      fileSplitterInput.setScanner(scanner);

      Attribute.AttributeMap.DefaultAttributeMap attributes = new Attribute.AttributeMap.DefaultAttributeMap();
      attributes.put(Context.DAGContext.APPLICATION_PATH,
          "target/" + className + "/" + methodName + "/" + Long.toHexString(System.currentTimeMillis()));

      context = new OperatorContextTestHelper.TestIdOperatorContext(0, attributes);
      fileMetadataSink = new CollectorTestSink<>();
      blockMetadataSink = new CollectorTestSink<>();
      resetSinks();
    }

    @Override
    protected void finished(Description description)
    {
      filePaths.clear();
      TestUtils.deleteTargetTestClassFolder(description);
    }

    private void resetSinks()
    {
      TestUtils.setSink(fileSplitterInput.filesMetadataOutput, fileMetadataSink);
      TestUtils.setSink(fileSplitterInput.blocksMetadataOutput, blockMetadataSink);
    }

    private void updateConfig(FSWindowDataManager fsWindowDataManager,
        long scanInterval, long blockSize, int blocksThreshold)
    {
      fileSplitterInput.setWindowDataManager(fsWindowDataManager);
      fileSplitterInput.getScanner().setScanIntervalMillis(scanInterval);
      fileSplitterInput.setBlockSize(blockSize);
      fileSplitterInput.setBlocksThreshold(blocksThreshold);
    }
  }

  @Rule
  public TestMeta testMeta = new TestMeta();
  
  private void window1TestHelper() throws InterruptedException
  {
    testMeta.fileSplitterInput.beginWindow(1);
    testMeta.scanner.semaphore.acquire();

    testMeta.fileSplitterInput.emitTuples();
    testMeta.fileSplitterInput.endWindow();

    Assert.assertEquals("File metadata", 12, testMeta.fileMetadataSink.collectedTuples.size());

    for (Object fileMetadata : testMeta.fileMetadataSink.collectedTuples) {
      FileSplitterInput.FileMetadata metadata = (FileSplitterInput.FileMetadata)fileMetadata;
      Assert.assertTrue("path: " + metadata.getFilePath(), testMeta.filePaths.contains(metadata.getFilePath()));
      Assert.assertNotNull("name: ", metadata.getFileName());
    }
    
    testMeta.fileMetadataSink.collectedTuples.clear();
  }

  @Test
  public void testFileMetadata() throws InterruptedException
  {
    testMeta.fileSplitterInput.setup(testMeta.context);
    window1TestHelper();
    testMeta.fileSplitterInput.teardown();
  }

  @Test
  public void testScannerFilterForDuplicates() throws InterruptedException
  {
    String filePath = testMeta.dataDirectory + Path.SEPARATOR + "file0.txt";
    testMeta.scanner = new MockScanner();
    testMeta.fileSplitterInput.setScanner(testMeta.scanner);
    testMeta.fileSplitterInput.getScanner().setScanIntervalMillis(500);
    testMeta.fileSplitterInput.getScanner().setFilePatternRegularExp(".*[.]txt");
    testMeta.fileSplitterInput.getScanner().setFiles(filePath);

    testMeta.fileSplitterInput.setup(testMeta.context);
    testMeta.fileSplitterInput.beginWindow(1);
    testMeta.scanner.semaphore.acquire();

    testMeta.fileSplitterInput.emitTuples();
    testMeta.fileSplitterInput.endWindow();

    testMeta.fileSplitterInput.beginWindow(2);
    testMeta.scanner.semaphore.release();
    testMeta.scanner.semaphore.acquire();
    testMeta.fileSplitterInput.emitTuples();
    testMeta.fileSplitterInput.endWindow();

    Assert.assertEquals("File metadata", 1, testMeta.fileMetadataSink.collectedTuples.size());
    for (Object fileMetadata : testMeta.fileMetadataSink.collectedTuples) {
      FileSplitterInput.FileMetadata metadata = (FileSplitterInput.FileMetadata)fileMetadata;
      Assert.assertTrue("path: " + metadata.getFilePath(), testMeta.filePaths.contains(metadata.getFilePath()));
      Assert.assertNotNull("name: ", metadata.getFileName());
    }

    testMeta.fileMetadataSink.collectedTuples.clear();
    testMeta.fileSplitterInput.teardown();
  }

  @Test
  public void testBlockMetadataNoSplit() throws InterruptedException
  {
    testMeta.fileSplitterInput.setup(testMeta.context);
    testMeta.fileSplitterInput.beginWindow(1);
    testMeta.scanner.semaphore.acquire();

    testMeta.fileSplitterInput.emitTuples();
    Assert.assertEquals("Blocks", 12, testMeta.blockMetadataSink.collectedTuples.size());
    for (Object blockMetadata : testMeta.blockMetadataSink.collectedTuples) {
      BlockMetadata.FileBlockMetadata metadata = (BlockMetadata.FileBlockMetadata)blockMetadata;
      Assert.assertTrue("path: " + metadata.getFilePath(), testMeta.filePaths.contains(metadata.getFilePath()));
    }
    testMeta.fileSplitterInput.teardown();
  }

  @Test
  public void testBlockMetadataWithSplit() throws InterruptedException
  {
    testMeta.fileSplitterInput.setBlockSize(2L);

    testMeta.fileSplitterInput.setup(testMeta.context);
    testMeta.fileSplitterInput.beginWindow(1);
    testMeta.scanner.semaphore.acquire();

    testMeta.fileSplitterInput.emitTuples();
    Assert.assertEquals("Files", 12, testMeta.fileMetadataSink.collectedTuples.size());

    int noOfBlocks = 0;
    for (int i = 0; i < 12; i++) {
      FileSplitterInput.FileMetadata fm = testMeta.fileMetadataSink.collectedTuples.get(i);
      File testFile = new File(testMeta.dataDirectory, fm.getFileName());
      noOfBlocks += (int)Math.ceil(testFile.length() / (2 * 1.0));
    }
    Assert.assertEquals("Blocks", noOfBlocks, testMeta.blockMetadataSink.collectedTuples.size());
    testMeta.fileSplitterInput.teardown();
  }

  @Test
  public void testIdempotency() throws InterruptedException
  {
    FSWindowDataManager fsIdempotentStorageManager = new FSWindowDataManager();
    testMeta.fileSplitterInput.setWindowDataManager(fsIdempotentStorageManager);

    testMeta.fileSplitterInput.setup(testMeta.context);
    //will emit window 1 from data directory
    window1TestHelper();
    testMeta.fileMetadataSink.clear();
    testMeta.blockMetadataSink.clear();
    testMeta.fileSplitterInput.teardown();

    testMeta.fileSplitterInput = KryoCloneUtils.cloneObject(testMeta.fileSplitterInput);
    testMeta.resetSinks();
    
    testMeta.fileSplitterInput.setup(testMeta.context);
    testMeta.fileSplitterInput.beginWindow(1);
    Assert.assertEquals("Blocks", 12, testMeta.blockMetadataSink.collectedTuples.size());
    for (Object blockMetadata : testMeta.blockMetadataSink.collectedTuples) {
      BlockMetadata.FileBlockMetadata metadata = (BlockMetadata.FileBlockMetadata)blockMetadata;
      Assert.assertTrue("path: " + metadata.getFilePath(), testMeta.filePaths.contains(metadata.getFilePath()));
    }
    testMeta.fileSplitterInput.teardown();
  }

  @Test
  public void testTimeScan() throws InterruptedException, IOException, TimeoutException
  {
    testMeta.fileSplitterInput.setup(testMeta.context);
    window1TestHelper();
    testMeta.fileMetadataSink.clear();
    testMeta.blockMetadataSink.clear();

    Thread.sleep(1000);
    //added a new relativeFilePath
    File f13 = new File(testMeta.dataDirectory, "file13" + ".txt");
    HashSet<String> lines = Sets.newHashSet();
    for (int line = 0; line < 2; line++) {
      lines.add("f13" + "l" + line);
    }
    FileUtils.write(f13, StringUtils.join(lines, '\n'));

    //window 2
    testMeta.fileSplitterInput.beginWindow(2);
    testMeta.scanner.semaphore.acquire();
    testMeta.fileSplitterInput.emitTuples();
    testMeta.fileSplitterInput.endWindow();

    Assert.assertEquals("window 2: files", 1, testMeta.fileMetadataSink.collectedTuples.size());
    Assert.assertEquals("window 2: blocks", 1, testMeta.blockMetadataSink.collectedTuples.size());
    testMeta.fileSplitterInput.teardown();
  }

  @Test
  public void testTrigger() throws InterruptedException, IOException, TimeoutException
  {
    testMeta.fileSplitterInput.getScanner().setScanIntervalMillis(60 * 1000);

    testMeta.fileSplitterInput.setup(testMeta.context);
    window1TestHelper();
    testMeta.fileMetadataSink.clear();
    testMeta.blockMetadataSink.clear();

    Thread.sleep(1000);
    //added a new relativeFilePath
    File f13 = new File(testMeta.dataDirectory, "file13" + ".txt");
    HashSet<String> lines = Sets.newHashSet();
    for (int line = 0; line < 2; line++) {
      lines.add("f13" + "l" + line);
    }
    FileUtils.write(f13, StringUtils.join(lines, '\n'));
    testMeta.fileSplitterInput.getScanner().setTrigger(true);

    //window 2
    testMeta.fileSplitterInput.beginWindow(2);
    testMeta.scanner.semaphore.acquire();
    testMeta.fileSplitterInput.emitTuples();
    testMeta.fileSplitterInput.endWindow();

    Assert.assertEquals("window 2: files", 1, testMeta.fileMetadataSink.collectedTuples.size());
    Assert.assertEquals("window 2: blocks", 1, testMeta.blockMetadataSink.collectedTuples.size());
    testMeta.fileSplitterInput.teardown();
  }
  
  private void blocksTestHelper() throws InterruptedException
  {
    testMeta.fileSplitterInput.beginWindow(1);
    testMeta.scanner.semaphore.acquire();
    testMeta.fileSplitterInput.emitTuples();
    testMeta.fileSplitterInput.endWindow();
    
    Assert.assertEquals("Blocks", 10, testMeta.blockMetadataSink.collectedTuples.size());
    
    for (int window = 2; window < 8; window++) {
      testMeta.fileSplitterInput.beginWindow(window);
      testMeta.fileSplitterInput.emitTuples();
      testMeta.fileSplitterInput.endWindow();
    }
    
    int noOfBlocks = 0;
    for (int i = 0; i < 12; i++) {
      File testFile = new File(testMeta.dataDirectory, "file" + i + ".txt");
      noOfBlocks += (int)Math.ceil(testFile.length() / (2 * 1.0));
    }

    Assert.assertEquals("Files", 12, testMeta.fileMetadataSink.collectedTuples.size());
    Assert.assertEquals("Blocks", noOfBlocks, testMeta.blockMetadataSink.collectedTuples.size());
  }

  @Test
  public void testBlocksThreshold() throws InterruptedException
  {
    testMeta.fileSplitterInput.setBlockSize(2L);
    testMeta.fileSplitterInput.setBlocksThreshold(10);
    testMeta.fileSplitterInput.setup(testMeta.context);
    blocksTestHelper();
    testMeta.fileSplitterInput.teardown();
  }

  private void recoveryTestHelper() throws InterruptedException
  {
    blocksTestHelper();
    testMeta.fileMetadataSink.clear();
    testMeta.blockMetadataSink.clear();
    testMeta.fileSplitterInput.teardown();

    testMeta.fileSplitterInput = KryoCloneUtils.cloneObject(testMeta.fileSplitterInput);
    testMeta.resetSinks();

    testMeta.fileSplitterInput.setup(testMeta.context);
    for (int i = 1; i < 8; i++) {
      testMeta.fileSplitterInput.beginWindow(i);
    }
    Assert.assertEquals("Files", 12, testMeta.fileMetadataSink.collectedTuples.size());
    Assert.assertEquals("Blocks", 62, testMeta.blockMetadataSink.collectedTuples.size());
  }

  @Test
  public void testIdempotencyWithBlocksThreshold() throws InterruptedException
  {
    FSWindowDataManager fsWindowDataManager = new FSWindowDataManager();
    testMeta.updateConfig(fsWindowDataManager, 500, 2L, 10);
    
    testMeta.fileSplitterInput.setup(testMeta.context);
    recoveryTestHelper();
    testMeta.fileSplitterInput.teardown();
  }

  @Test
  public void testFirstWindowAfterRecovery() throws IOException, InterruptedException
  {
    FSWindowDataManager fsWindowDataManager = new FSWindowDataManager();
    testMeta.updateConfig(fsWindowDataManager, 500, 2L, 10);
    testMeta.fileSplitterInput.setup(testMeta.context);

    recoveryTestHelper();
    
    Thread.sleep(1000);
    HashSet<String> lines = Sets.newHashSet();
    for (int line = 2; line < 4; line++) {
      lines.add("f13" + "l" + line);
    }
    File f13 = new File(testMeta.dataDirectory, "file13" + ".txt");

    FileUtils.writeLines(f13, lines, true);

    testMeta.fileMetadataSink.clear();
    testMeta.blockMetadataSink.clear();

    testMeta.fileSplitterInput.beginWindow(8);
    ((MockScanner)testMeta.fileSplitterInput.getScanner()).semaphore.acquire();
    testMeta.fileSplitterInput.emitTuples();
    testMeta.fileSplitterInput.endWindow();

    Assert.assertEquals("Files", 1, testMeta.fileMetadataSink.collectedTuples.size());
    Assert.assertEquals("Blocks", 6, testMeta.blockMetadataSink.collectedTuples.size());
    testMeta.fileSplitterInput.teardown();
  }

  @Test
  public void testRecoveryOfPartialFile() throws InterruptedException
  {
    FSWindowDataManager fsIdempotentStorageManager = new FSWindowDataManager();
    testMeta.updateConfig(fsIdempotentStorageManager, 500L, 2L, 2);

    FileSplitterInput checkpointedInput = KryoCloneUtils.cloneObject(testMeta.fileSplitterInput);

    testMeta.fileSplitterInput.setup(testMeta.context);

    testMeta.fileSplitterInput.beginWindow(1);

    testMeta.scanner.semaphore.acquire();
    testMeta.fileSplitterInput.emitTuples();
    testMeta.fileSplitterInput.endWindow();

    //file0.txt has just 5 blocks. Since blocks threshold is 2, only 2 are emitted.
    Assert.assertEquals("Files", 1, testMeta.fileMetadataSink.collectedTuples.size());
    Assert.assertEquals("Blocks", 2, testMeta.blockMetadataSink.collectedTuples.size());

    testMeta.fileMetadataSink.clear();
    testMeta.blockMetadataSink.clear();

    testMeta.fileSplitterInput.teardown();

    //there was a failure and the operator was re-deployed
    testMeta.fileSplitterInput = checkpointedInput;
    testMeta.resetSinks();

    testMeta.fileSplitterInput.setup(testMeta.context);
    testMeta.fileSplitterInput.beginWindow(1);

    Assert.assertEquals("Recovered Files", 1, testMeta.fileMetadataSink.collectedTuples.size());
    Assert.assertEquals("Recovered Blocks", 2, testMeta.blockMetadataSink.collectedTuples.size());

    testMeta.fileSplitterInput.beginWindow(2);
    testMeta.fileSplitterInput.emitTuples();
    testMeta.fileSplitterInput.endWindow();

    Assert.assertEquals("Blocks", 4, testMeta.blockMetadataSink.collectedTuples.size());

    String file1 = testMeta.fileMetadataSink.collectedTuples.get(0).getFileName();

    testMeta.fileMetadataSink.clear();
    testMeta.blockMetadataSink.clear();

    testMeta.fileSplitterInput.beginWindow(3);
    ((MockScanner)testMeta.fileSplitterInput.getScanner()).semaphore.acquire();
    testMeta.fileSplitterInput.emitTuples();
    testMeta.fileSplitterInput.endWindow();

    Assert.assertEquals("New file", 1, testMeta.fileMetadataSink.collectedTuples.size());
    Assert.assertEquals("Blocks", 2, testMeta.blockMetadataSink.collectedTuples.size());

    String file2 = testMeta.fileMetadataSink.collectedTuples.get(0).getFileName();

    Assert.assertTrue("Block file name 0",
        testMeta.blockMetadataSink.collectedTuples.get(0).getFilePath().endsWith(file1));
    Assert.assertTrue("Block file name 1",
        testMeta.blockMetadataSink.collectedTuples.get(1).getFilePath().endsWith(file2));
    testMeta.fileSplitterInput.teardown();
  }

  @Test
  public void testRecursive() throws InterruptedException, IOException
  {
    testMeta.fileSplitterInput.setup(testMeta.context);
    window1TestHelper();
    testMeta.fileMetadataSink.clear();
    testMeta.blockMetadataSink.clear();

    Thread.sleep(1000);
    //added a new relativeFilePath
    File f13 = new File(testMeta.dataDirectory + "/child", "file13" + ".txt");
    HashSet<String> lines = Sets.newHashSet();
    for (int line = 0; line < 2; line++) {
      lines.add("f13" + "l" + line);
    }
    FileUtils.write(f13, StringUtils.join(lines, '\n'));

    //window 2
    testMeta.fileSplitterInput.beginWindow(2);
    testMeta.scanner.semaphore.acquire();
    testMeta.fileSplitterInput.emitTuples();
    testMeta.fileSplitterInput.endWindow();

    Assert.assertEquals("window 2: files", 2, testMeta.fileMetadataSink.collectedTuples.size());
    Assert.assertEquals("window 2: blocks", 1, testMeta.blockMetadataSink.collectedTuples.size());
    testMeta.fileSplitterInput.teardown();
  }

  @Test
  public void testSingleFile() throws InterruptedException, IOException
  {
    testMeta.fileSplitterInput.setScanner(new MockScanner());
    testMeta.fileSplitterInput.getScanner().setFiles(testMeta.dataDirectory + "/file1.txt");

    testMeta.fileSplitterInput.setup(testMeta.context);
    testMeta.fileSplitterInput.beginWindow(1);
    ((MockScanner)testMeta.fileSplitterInput.getScanner()).semaphore.acquire();

    testMeta.fileSplitterInput.emitTuples();
    testMeta.fileSplitterInput.endWindow();
    Assert.assertEquals("File metadata count", 1, testMeta.fileMetadataSink.collectedTuples.size());
    Assert.assertEquals("File metadata", new File(testMeta.dataDirectory + "/file1.txt").getAbsolutePath(),
        testMeta.fileMetadataSink.collectedTuples.get(0).getFilePath());
    testMeta.fileSplitterInput.teardown();
  }

  @Test
  public void testRecoveryOfBlockMetadataIterator() throws InterruptedException
  {
    FSWindowDataManager fsWindowDataManager = new FSWindowDataManager();
    testMeta.updateConfig(fsWindowDataManager, 500L, 2L, 2);
    
    testMeta.fileSplitterInput.setup(testMeta.context);
    testMeta.fileSplitterInput.beginWindow(1);

    ((MockScanner)testMeta.fileSplitterInput.getScanner()).semaphore.acquire();
    testMeta.fileSplitterInput.emitTuples();
    testMeta.fileSplitterInput.endWindow();

    //file0.txt has just 5 blocks. Since blocks threshold is 2, only 2 are emitted.
    Assert.assertEquals("Files", 1, testMeta.fileMetadataSink.collectedTuples.size());
    Assert.assertEquals("Blocks", 2, testMeta.blockMetadataSink.collectedTuples.size());

    testMeta.fileMetadataSink.clear();
    testMeta.blockMetadataSink.clear();

    //At this point the operator was check-pointed and then there was a failure.
    testMeta.fileSplitterInput.teardown();

    //The operator was restored from persisted state and re-deployed.
    testMeta.fileSplitterInput = KryoCloneUtils.cloneObject(testMeta.fileSplitterInput);
    TestUtils.setSink(testMeta.fileSplitterInput.blocksMetadataOutput, testMeta.blockMetadataSink);
    TestUtils.setSink(testMeta.fileSplitterInput.filesMetadataOutput, testMeta.fileMetadataSink);

    testMeta.fileSplitterInput.setup(testMeta.context);
    testMeta.fileSplitterInput.beginWindow(1);

    Assert.assertEquals("Recovered Files", 1, testMeta.fileMetadataSink.collectedTuples.size());
    Assert.assertEquals("Recovered Blocks", 2, testMeta.blockMetadataSink.collectedTuples.size());

    testMeta.fileSplitterInput.teardown();
  }

  @Test
  public void testFileModificationTest() throws InterruptedException, IOException, TimeoutException
  {
    testMeta.fileSplitterInput.getScanner().setScanIntervalMillis(60 * 1000);
    testMeta.fileSplitterInput.setup(testMeta.context);
    window1TestHelper();

    testMeta.fileMetadataSink.clear();
    testMeta.blockMetadataSink.clear();

    Thread.sleep(1000);
    //change a file , this will not change mtime of the file.
    File f12 = new File(testMeta.dataDirectory, "file11" + ".txt");
    HashSet<String> lines = Sets.newHashSet();
    for (int line = 0; line < 2; line++) {
      lines.add("f13" + "l" + line);
    }
    /* Need to use FileWriter, FileUtils changes the directory timestamp when
       file is changed. */
    FileWriter fout = new FileWriter(f12, true);
    fout.write(StringUtils.join(lines, '\n').toCharArray());
    fout.close();
    testMeta.fileSplitterInput.getScanner().setTrigger(true);

    //window 2
    testMeta.fileSplitterInput.beginWindow(2);
    testMeta.scanner.semaphore.acquire();
    testMeta.fileSplitterInput.emitTuples();
    testMeta.fileSplitterInput.endWindow();

    Assert.assertEquals("window 2: files", 1, testMeta.fileMetadataSink.collectedTuples.size());
    Assert.assertEquals("window 2: blocks", 1, testMeta.blockMetadataSink.collectedTuples.size());

    //window 3
    testMeta.fileMetadataSink.clear();
    testMeta.blockMetadataSink.clear();
    testMeta.scanner.setTrigger(true);
    testMeta.scanner.semaphore.release();
    testMeta.fileSplitterInput.beginWindow(3);
    Thread.sleep(1000);
    testMeta.scanner.semaphore.acquire();
    testMeta.fileSplitterInput.emitTuples();
    testMeta.fileSplitterInput.endWindow();

    Assert.assertEquals("window 2: files", 0, testMeta.fileMetadataSink.collectedTuples.size());
    Assert.assertEquals("window 2: blocks", 0, testMeta.blockMetadataSink.collectedTuples.size());
    
    testMeta.fileSplitterInput.teardown();
  }

  @Test
  public void testMultipleNestedInput() throws IOException, InterruptedException
  {
    File subDir = new File(testMeta.dataDirectory, "subDir");
    subDir.mkdir();
    File file = new File(subDir, "file.txt");
    FileWriter fout = new FileWriter(file, true);
    fout.write(StringUtils.join("testData", '\n').toCharArray());
    fout.close();
    String files = testMeta.scanner.getFiles();
    files = files.concat("," + subDir.getAbsolutePath());
    List<String> expectedFiles = new ArrayList<String>();
    expectedFiles.addAll(testMeta.filePaths);
    expectedFiles.add(subDir.getAbsolutePath());
    expectedFiles.add(file.getAbsolutePath());
    expectedFiles.add(file.getAbsolutePath()); // file will be discovered twice w.t.r. two input dirs

    testMeta.fileSplitterInput.setScanner(new MockScanner());
    testMeta.fileSplitterInput.getScanner().setScanIntervalMillis(60 * 1000);
    testMeta.fileSplitterInput.getScanner().setFiles(files);
    testMeta.fileSplitterInput.setup(testMeta.context);

    testMeta.fileSplitterInput.beginWindow(1);
    ((MockScanner)testMeta.fileSplitterInput.getScanner()).semaphore.acquire();

    testMeta.fileSplitterInput.emitTuples();
    testMeta.fileSplitterInput.endWindow();
    Assert.assertEquals("File metadata", 15, testMeta.fileMetadataSink.collectedTuples.size());
    for (Object fileMetadata : testMeta.fileMetadataSink.collectedTuples) {
      FileSplitterInput.FileMetadata metadata = (FileSplitterInput.FileMetadata)fileMetadata;
      Assert.assertTrue("path: " + metadata.getFilePath(), expectedFiles.contains(metadata.getFilePath()));
      Assert.assertNotNull("name: ", metadata.getFileName());
    }

    testMeta.fileMetadataSink.collectedTuples.clear();
    testMeta.fileSplitterInput.teardown();
  }

  @Test
  public void testEmptyDirCopy() throws InterruptedException
  {
    File emptyDir = new File(testMeta.dataDirectory, "emptyDir");
    emptyDir.mkdirs();
    testMeta.fileSplitterInput.setScanner(new MockScanner());
    testMeta.fileSplitterInput.getScanner().regex = null;
    testMeta.fileSplitterInput.getScanner().setFiles(testMeta.dataDirectory + "/emptyDir");

    testMeta.fileSplitterInput.setup(testMeta.context);
    testMeta.fileSplitterInput.beginWindow(1);
    ((MockScanner)testMeta.fileSplitterInput.getScanner()).semaphore.acquire();
    testMeta.fileSplitterInput.emitTuples();
    testMeta.fileSplitterInput.endWindow();
    Assert.assertEquals("File metadata count", 1, testMeta.fileMetadataSink.collectedTuples.size());
    Assert.assertEquals("Empty directory not copied.", emptyDir.getName(),
        testMeta.fileMetadataSink.collectedTuples.get(0).getFileName());
    testMeta.fileSplitterInput.teardown();
  }

  private static class MockScanner extends FileSplitterInput.TimeBasedDirectoryScanner
  {
    transient Semaphore semaphore;

    private MockScanner()
    {
      super();
      this.semaphore = new Semaphore(0);
    }

    @Override
    protected void scanIterationComplete()
    {
      if (getNumDiscoveredPerIteration() > 0) {
        semaphore.release();
      }
      super.scanIterationComplete();
    }
  }
  
  private static final Logger LOG = LoggerFactory.getLogger(FileSplitterInputTest.class);
}
