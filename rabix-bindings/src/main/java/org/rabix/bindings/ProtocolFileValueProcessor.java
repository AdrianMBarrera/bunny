package org.rabix.bindings;

import java.util.Set;

import org.rabix.bindings.model.FileValue;
import org.rabix.bindings.model.Job;

public interface ProtocolFileValueProcessor {

  Set<FileValue> getInputFiles(Job job) throws BindingException;
  
  Set<FileValue> getOutputFiles(Job job, boolean onlyVisiblePorts) throws BindingException;
 
  Job updateInputFiles(Job job, Set<FileValue> inputFiles) throws BindingException;

  Job updateOutputFiles(Job job, Set<FileValue> outputFiles) throws BindingException;
  
}
