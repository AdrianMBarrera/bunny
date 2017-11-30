package org.rabix.backend.tes.service.impl;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.apache.commons.configuration.Configuration;
import org.rabix.backend.tes.service.TESStorageException;
import org.rabix.backend.tes.service.TESStorageService;
import org.rabix.bindings.BindingException;
import org.rabix.bindings.helper.FileValueHelper;
import org.rabix.bindings.model.DirectoryValue;
import org.rabix.bindings.model.FileValue;
import org.rabix.bindings.model.FileValue.FileType;
import org.rabix.bindings.model.Job;
import org.rabix.bindings.transformer.FileTransformer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.ImmutableMap;
import com.google.inject.Inject;
import com.upplication.s3fs.AmazonS3Factory;

public class LocalTESStorageServiceImpl implements TESStorageService {

  private final static Logger logger = LoggerFactory.getLogger(LocalTESStorageServiceImpl.class);

  private final Path localFileStorage;
  private final Path storageBase;

  @Inject
  public LocalTESStorageServiceImpl(Configuration configuration) {
    localFileStorage = Paths.get(configuration.getString("backend.execution.directory"));
    String storageConfig = configuration.getString("rabix.tes.storage.base", localFileStorage.toString());
    if (storageConfig.startsWith("s3")) {
      if (configuration.containsKey(AmazonS3Factory.ACCESS_KEY) && configuration.containsKey(AmazonS3Factory.SECRET_KEY)) {
        Map<String, ?> env = ImmutableMap.<String, Object>builder().put(AmazonS3Factory.ACCESS_KEY, configuration.getString(AmazonS3Factory.ACCESS_KEY))
            .put(AmazonS3Factory.SECRET_KEY, configuration.getString(AmazonS3Factory.SECRET_KEY)).build();
        try {
          FileSystem newFileSystem = FileSystems.newFileSystem(URI.create(storageConfig), env, Thread.currentThread().getContextClassLoader());
        } catch (IOException e) {
          logger.error("Failed to register s3 filesystem");
        }
      }
    }
    URI uri = URI.create(storageConfig);
    if (uri.getScheme() == null) {
      try {
        uri = new URI("file", uri.toString(), null);
      } catch (URISyntaxException e) {
        logger.error("Failed to parse storage path", e);
      }
    }
    storageBase = Paths.get(uri);
  }

  @Override
  public Job transformInputFiles(Job job) throws BindingException {
    try {
      Path workdir = workDir(job);
      List<FileValue> flat = new ArrayList<>();
      return FileValueHelper.updateInputFiles(job, new FileTransformer() {
        @Override
        public FileValue transform(FileValue fileValue) throws BindingException {
          try {
            stageFile(workdir, fileValue, flat);
          } catch (TESStorageException e) {
            throw new BindingException(e.getMessage());
          }
          return fileValue;
        }
      });
    } catch (BindingException e) {
      logger.error("Failed to stage input files", e);
      throw new BindingException("Failed to stage input files", e);
    }

  }

  public List<FileValue> stageFile(Path workdir, FileValue fileValue) throws TESStorageException {
    ArrayList<FileValue> flat = new ArrayList<>();
    this.stageFile(workdir, fileValue, flat);
    return flat;
  }

  public void stageFile(Path workdir, FileValue fileValue, List<FileValue> flat) throws TESStorageException {
    String path = fileValue.getPath();
    String location = fileValue.getLocation();
    URI locationUri;
    Path locationPath;
    if (location == null) {
      locationPath = Paths.get(path);
      locationUri = locationPath.toUri();
      fileValue.setLocation(locationUri.toString());
    } else {
      locationUri = URI.create(location);
      locationPath = Paths.get(locationUri);
    }
    if ((location == null || locationUri.getScheme().equals("file")) && !fileValue.getType().equals(FileType.Directory)) {
      try {
        Path staged = workdir.resolveSibling("stagedinputs" + workdir.hashCode()).resolve("./" + path).normalize();
        if (!Files.exists(staged)) {
          Files.createDirectories(staged.getParent());
          Files.copy(locationPath, staged);
        }
        fileValue.setLocation(staged.toUri().toString());
      } catch (IOException e) {
        throw new TESStorageException(e.getMessage());
      }
    }
    for (FileValue f : fileValue.getSecondaryFiles()) {
      stageFile(workdir, f, flat);
    }
    if (fileValue instanceof DirectoryValue) {
      List<FileValue> listing = ((DirectoryValue) fileValue).getListing();
      for (FileValue f : listing) {
        stageFile(workdir, f, flat);
      }
    }
    flat.add(fileValue);
  }

  @Override
  public Path workDir(Job job) {
    return storageBase.resolve(job.getRootId().toString()).resolve(job.getName().replaceAll("\\.", "/"));
  }

  @Override
  public Path localDir(Job job) {
    return localFileStorage.resolve(job.getRootId().toString()).resolve(job.getName().replaceAll("\\.", "/"));
  }
}
