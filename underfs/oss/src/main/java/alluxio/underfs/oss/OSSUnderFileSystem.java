/*
 * The Alluxio Open Foundation licenses this work under the Apache License, version 2.0
 * (the "License"). You may not use this work except in compliance with the License, which is
 * available at www.apache.org/licenses/LICENSE-2.0
 *
 * This software is distributed on an "AS IS" basis, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied, as more fully set forth in the License.
 *
 * See the NOTICE file distributed with this work for information regarding copyright ownership.
 */

package alluxio.underfs.oss;

import alluxio.AlluxioURI;
import alluxio.Configuration;
import alluxio.Constants;
import alluxio.PropertyKey;
import alluxio.underfs.ObjectUnderFileSystem;
import alluxio.underfs.UnderFileSystem;
import alluxio.underfs.options.DeleteOptions;
import alluxio.underfs.options.MkdirsOptions;
import alluxio.util.io.PathUtils;

import com.aliyun.oss.ClientConfiguration;
import com.aliyun.oss.OSSClient;
import com.aliyun.oss.ServiceException;
import com.aliyun.oss.model.ListObjectsRequest;
import com.aliyun.oss.model.OSSObjectSummary;
import com.aliyun.oss.model.ObjectListing;
import com.aliyun.oss.model.ObjectMetadata;
import com.google.common.base.Preconditions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;

import javax.annotation.concurrent.ThreadSafe;

/**
 * Aliyun OSS {@link UnderFileSystem} implementation.
 */
@ThreadSafe
public final class OSSUnderFileSystem extends ObjectUnderFileSystem {
  private static final Logger LOG = LoggerFactory.getLogger(Constants.LOGGER_TYPE);

  /** Suffix for an empty file to flag it as a directory. */
  private static final String FOLDER_SUFFIX = "_$folder$";

  /** Aliyun OSS client. */
  private final OSSClient mClient;

  /** Bucket name of user's configured Alluxio bucket. */
  private final String mBucketName;

  /**
   * Constructs a new instance of {@link OSSUnderFileSystem}.
   *
   * @param uri the {@link AlluxioURI} for this UFS
   * @return the created {@link OSSUnderFileSystem} instance
   * @throws Exception when a connection to GCS could not be created
   */
  public static OSSUnderFileSystem createInstance(AlluxioURI uri) throws Exception {
    String bucketName = uri.getHost();
    Preconditions.checkArgument(Configuration.containsKey(PropertyKey.OSS_ACCESS_KEY),
        "Property " + PropertyKey.OSS_ACCESS_KEY + " is required to connect to OSS");
    Preconditions.checkArgument(Configuration.containsKey(PropertyKey.OSS_SECRET_KEY),
        "Property " + PropertyKey.OSS_SECRET_KEY + " is required to connect to OSS");
    Preconditions.checkArgument(Configuration.containsKey(PropertyKey.OSS_ENDPOINT_KEY),
        "Property " + PropertyKey.OSS_ENDPOINT_KEY + " is required to connect to OSS");
    String accessId = Configuration.get(PropertyKey.OSS_ACCESS_KEY);
    String accessKey = Configuration.get(PropertyKey.OSS_SECRET_KEY);
    String endPoint = Configuration.get(PropertyKey.OSS_ENDPOINT_KEY);

    ClientConfiguration ossClientConf = initializeOSSClientConfig();
    OSSClient ossClient = new OSSClient(endPoint, accessId, accessKey, ossClientConf);

    return new OSSUnderFileSystem(uri, ossClient, bucketName);
  }

  /**
   * Constructor for {@link OSSUnderFileSystem}.
   *
   * @param uri the {@link AlluxioURI} for this UFS
   * @param ossClient Aliyun OSS client
   * @param bucketName bucket name of user's configured Alluxio bucket
   * @param bucketPrefix prefix of the bucket
   */
  protected OSSUnderFileSystem(AlluxioURI uri,
      OSSClient ossClient,
      String bucketName) {
    super(uri);
    mClient = ossClient;
    mBucketName = bucketName;
  }

  @Override
  public String getUnderFSType() {
    return "oss";
  }

  @Override
  protected OutputStream createOutputStream(String path) throws IOException {
    return new OSSOutputStream(mBucketName, stripPrefixIfPresent(path), mClient);
  }

  @Override
  public long getFileSize(String path) throws IOException {
    ObjectMetadata objectMeta = getObjectDetails(path);
    if (objectMeta != null) {
      return objectMeta.getContentLength();
    } else {
      throw new FileNotFoundException(path);
    }
  }

  @Override
  public long getModificationTimeMs(String path) throws IOException {
    ObjectMetadata objectMeta = getObjectDetails(path);
    if (objectMeta != null) {
      return objectMeta.getLastModified().getTime();
    } else {
      throw new FileNotFoundException(path);
    }
  }

  @Override
  public boolean isDirectory(String key) {
    // Root is a folder
    if (isRoot(key)) {
      return true;
    }
    try {
      String keyAsFolder = convertToFolderName(stripPrefixIfPresent(key));
      mClient.getObjectMetadata(mBucketName, keyAsFolder);
      // If no exception is thrown, the key exists as a folder
      return true;
    } catch (ServiceException s) {
      // It is possible that the folder has not been encoded as a _$folder$ file
      try {
        // Check if anything begins with <path>/
        String path = PathUtils.normalizePath(stripPrefixIfPresent(key), PATH_SEPARATOR);
        ListObjectsRequest listObjectsRequest = new ListObjectsRequest(mBucketName);
        listObjectsRequest.setPrefix(path);
        listObjectsRequest.setDelimiter(PATH_SEPARATOR);
        ObjectListing listing = mClient.listObjects(listObjectsRequest);
        if (!listing.getObjectSummaries().isEmpty()) {
          mkdirsInternal(path);
          return true;
        } else {
          return false;
        }
      } catch (ServiceException s2) {
        return false;
      }
    }
  }

  @Override
  public boolean isFile(String path) throws IOException {
    try {
      return mClient.getObjectMetadata(mBucketName, stripPrefixIfPresent(path)) != null;
    } catch (ServiceException e) {
      return false;
    }
  }

  @Override
  public boolean mkdirs(String path, boolean createParent) throws IOException {
    return mkdirs(path, MkdirsOptions.defaults().setCreateParent(createParent));

  }

  @Override
  public boolean mkdirs(String path, MkdirsOptions options) throws IOException {
    if (path == null) {
      return false;
    }
    if (isDirectory(path)) {
      return true;
    }
    if (isFile(path)) {
      LOG.error("Cannot create directory {} because it is already a file.", path);
      return false;
    }
    if (!options.getCreateParent()) {
      if (parentExists(path)) {
        // Parent directory exists
        return mkdirsInternal(path);
      } else {
        LOG.error("Cannot create directory {} because parent does not exist", path);
        return false;
      }
    }
    // Parent directories should be created
    if (parentExists(path)) {
      // Parent directory exists
      return mkdirsInternal(path);
    } else {
      String parentKey = getParentKey(path);
      // Recursively make the parent folders
      return mkdirs(parentKey, true) && mkdirsInternal(path);
    }
  }

  @Override
  public InputStream open(String path) throws IOException {
    try {
      path = stripPrefixIfPresent(path);
      return new OSSInputStream(mBucketName, path, mClient);
    } catch (ServiceException e) {
      LOG.error("Failed to open file: {}", path, e);
      return null;
    }
  }

  @Override
  public boolean renameDirectory(String src, String dst) throws IOException {
    String[] children = list(src);
    if (children == null) {
      LOG.error("Failed to list directory {}, aborting rename.", src);
      return false;
    }
    if (isFile(dst) || isDirectory(dst)) {
      LOG.error("Unable to rename {} to {} because destination already exists.", src, dst);
      return false;
    }
    // Source exists and is a file, and destination does not exist
    // Rename the source folder first
    if (!copy(convertToFolderName(src), convertToFolderName(dst))) {
      return false;
    }
    // Rename each child in the src folder to destination/child
    for (String child : children) {
      String childSrcPath = PathUtils.concatPath(src, child);
      String childDstPath = PathUtils.concatPath(dst, child);
      boolean success;
      if (isDirectory(childSrcPath)) {
        // Recursive call
        success = renameDirectory(childSrcPath, childDstPath);
      } else {
        success = renameFile(childSrcPath, childDstPath);
      }
      if (!success) {
        LOG.error("Failed to rename path {}, aborting rename.", child);
        return false;
      }
    }
    // Delete src and everything under src
    return deleteDirectory(src, DeleteOptions.defaults().setRecursive(true));
  }

  @Override
  public boolean renameFile(String src, String dst) throws IOException {
    if (!isFile(src)) {
      LOG.error("Unable to rename {} to {} because source does not exist or is a directory.",
          src, dst);
      return false;
    }
    if (isFile(dst) || isDirectory(dst)) {
      LOG.error("Unable to rename {} to {} because destination already exists.", src, dst);
      return false;
    }
    // Source is a file and Destination does not exist
    return copy(src, dst) && deleteInternal(src);
  }

  // No ACL integration currently, no-op
  @Override
  public void setOwner(String path, String user, String group) {}

  // No ACL integration currently, no-op
  @Override
  public void setMode(String path, short mode) throws IOException {}

  // No ACL integration currently, returns default empty value
  @Override
  public String getOwner(String path) throws IOException {
    return "";
  }

  // No ACL integration currently, returns default empty value
  @Override
  public String getGroup(String path) throws IOException {
    return "";
  }

  // No ACL integration currently, returns default value
  @Override
  public short getMode(String path) throws IOException {
    return Constants.DEFAULT_FILE_SYSTEM_MODE;
  }

  /**
   * Copies an object to another key.
   *
   * @param src the source key to copy
   * @param dst the destination key to copy to
   * @return true if the operation was successful, false otherwise
   */
  private boolean copy(String src, String dst) {
    try {
      src = stripPrefixIfPresent(src);
      dst = stripPrefixIfPresent(dst);
      LOG.info("Copying {} to {}", src, dst);
      mClient.copyObject(mBucketName, src, mBucketName, dst);
      return true;
    } catch (ServiceException e) {
      LOG.error("Failed to rename file {} to {}", src, dst, e);
      return false;
    }
  }

  @Override
  protected boolean deleteInternal(String key) {
    try {
      mClient.deleteObject(mBucketName, stripPrefixIfPresent(key));
    } catch (ServiceException e) {
      LOG.error("Failed to delete {}", key, e);
      return false;
    }
    return true;
  }

  /**
   * @param key the key to get the object details of
   * @return {@link ObjectMetadata} of the key, or null if the key does not exist
   */
  private ObjectMetadata getObjectDetails(String key) {
    try {
      if (isDirectory(key)) {
        String keyAsFolder = convertToFolderName(stripPrefixIfPresent(key));
        return mClient.getObjectMetadata(mBucketName, keyAsFolder);
      } else {
        return mClient.getObjectMetadata(mBucketName, stripPrefixIfPresent(key));
      }
    } catch (ServiceException e) {
      LOG.warn("Failed to get Object {}, return null", key, e);
      return null;
    }
  }

  @Override
  protected String getFolderSuffix() {
    return FOLDER_SUFFIX;
  }

  @Override
  protected ObjectListingResult getObjectListing(String path, boolean recursive)
      throws IOException {
    return new GCSObjectListingResult(path, recursive);
  }

  /**
   * Wrapper over GCS {@link StorageObjectsChunk}.
   */
  final class GCSObjectListingResult implements ObjectListingResult {
    String mPath;
    ListObjectsRequest mRequest;
    ObjectListing mResult;

    public GCSObjectListingResult(String path, boolean recursive) {
      String delimiter = recursive ? "" : PATH_SEPARATOR;
      mPath = path;
      mRequest = new ListObjectsRequest(mBucketName);
      mRequest.setPrefix(path);
      mRequest.setMaxKeys(LISTING_LENGTH);
      mRequest.setDelimiter(delimiter);
    }

    @Override
    public String[] getObjectNames() {
      if (mResult == null) {
        return null;
      }
      List<OSSObjectSummary> objects = mResult.getObjectSummaries();
      String[] ret = new String[objects.size()];
      int i = 0;
      for (OSSObjectSummary obj : objects) {
        ret[i] = obj.getKey();
      }
      return ret;
    }

    @Override
    public String[] getCommonPrefixes() {
      if (mResult == null) {
        return null;
      }
      List<String> res = mResult.getCommonPrefixes();
      return res.toArray(new String[res.size()]);
    }

    @Override
    public ObjectListingResult getNextChunk() throws IOException {
      if (mResult != null && !mResult.isTruncated()) {
        return null;
      }
      try {
        mResult = mClient.listObjects(mRequest);
      } catch (ServiceException e) {
        LOG.error("Failed to list path {}", mPath, e);
        return null;
      }
      return this;
    }
  }

  /**
   * Creates an OSS {@code ClientConfiguration} using an Alluxio Configuration.
   *
   * @return the OSS {@link ClientConfiguration}
   */
  private static ClientConfiguration initializeOSSClientConfig() {
    ClientConfiguration ossClientConf = new ClientConfiguration();
    ossClientConf.setConnectionTimeout(
        Configuration.getInt(PropertyKey.UNDERFS_OSS_CONNECT_TIMEOUT));
    ossClientConf.setSocketTimeout(
        Configuration.getInt(PropertyKey.UNDERFS_OSS_SOCKET_TIMEOUT));
    ossClientConf.setConnectionTTL(Configuration.getLong(PropertyKey.UNDERFS_OSS_CONNECT_TTL));
    ossClientConf.setMaxConnections(Configuration.getInt(PropertyKey.UNDERFS_OSS_CONNECT_MAX));
    return ossClientConf;
  }

  @Override
  protected String getRootKey() {
    return Constants.HEADER_OSS + mBucketName;
  }

  @Override
  protected boolean mkdirsInternal(String key) {
    try {
      String keyAsFolder = convertToFolderName(stripPrefixIfPresent(key));
      ObjectMetadata objMeta = new ObjectMetadata();
      objMeta.setContentLength(0);
      mClient.putObject(mBucketName, keyAsFolder, new ByteArrayInputStream(new byte[0]), objMeta);
      return true;
    } catch (ServiceException e) {
      LOG.error("Failed to create directory: {}", key, e);
      return false;
    }
  }
}
