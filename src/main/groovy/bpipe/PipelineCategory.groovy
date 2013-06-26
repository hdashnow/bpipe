/*
 * Copyright (c) 2012 MCRI, authors
 *
 * Permission is hereby granted, free of charge, to any person
 * obtaining a copy of this software and associated documentation
 * files (the "Software"), to deal in the Software without
 * restriction, including without limitation the rights to use,
 * copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the
 * Software is furnished to do so, subject to the following
 * conditions:
 *
 * The above copyright notice and this permission notice shall be
 * included in all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
 * EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES
 * OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
 * NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT
 * HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY,
 * WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING
 * FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR
 * THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */
package bpipe

import groovy.lang.Closure;

import java.io.FileOutputStream;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

import groovy.util.logging.Log;
import java.util.regex.Pattern;

import org.codehaus.groovy.runtime.StackTraceUtils;

import static Utils.*

/**
 * A category that adds default Bpipe functions to closures
 * to enable basic operators such as the + operator to work.
 */
@Log
class PipelineCategory {
    
    static Map closureNames = [:]
    
    /**
     * Map of stage name to body wrapper - a closure that should be 
     * called instead of the body, passing the body as a parameter.
     * This is how predeclared Transform and Filters work.
     */
    static Map wrappers = [:]
    
    static Closure getAt(Closure c, String... params) {
        return c
    }
   
    static Closure cfg(Closure c, Map params) {
        def pc = new ParameterizedClosure(params, c)
        if(closureNames.containsKey(c))
            closureNames[pc] = closureNames[c]
        return pc
    }
    
   static Closure using(Closure c, Map params) {
        cfg(c,params)
    }
    
    static Closure with(Closure c, Map params) {
        cfg(c,params)
    }
    
    static Closure bitwiseNegate(Closure c, Map params) {
        cfg(c,params)
    }
    
    static Closure cfg(Closure c, Object... args) {
        c.binding.variables["args"] = args
        return c
    }
    
    static Closure using(Closure c, Object... args) {
        cfg(c,args)
    }
    
    static Closure with(Closure c, Object... args) {
        cfg(c,args)
    }
    
    static Closure bitwiseNegate(Closure c, Object... args) {
        cfg(c,args)
    }
	
     /**
     * Joins two closures representing pipeline stages together by
     * creating wrapping closure that executes each one in turn.  This is the 
     * basis of Bpipes's + syntax for joining sequential pipeline stages.
     */
    static Object plus(Closure c, Closure other) {
        
        // What we return is actually a closure to be executed later
        // when the pipeline is run.  
        def result  = {  input1 ->
            
            Pipeline pipeline = Pipeline.currentRuntimePipeline.get()
             
            def currentStage = new PipelineStage(pipeline.createContext(), c)
            pipeline.addStage(currentStage)
            currentStage.context.setInput(input1)
            currentStage.run()
            Dependencies.instance.checkFiles(currentStage.context.@output)
                    
            // If the stage did not return any outputs then we assume
            // that the inputs to the next stage are the same as the inputs
            // to the previous stage
            def nextInputs = currentStage.context.nextInputs
            log.info "Next inputs from stage = $nextInputs"
            if(nextInputs == null) {
                nextInputs = currentStage.context.@input
            }
                
            log.info "Checking inputs for next stage:  $nextInputs"
            Dependencies.instance.checkFiles(nextInputs)
                
            currentStage = new PipelineStage(pipeline.createContext(), other)
            currentStage.context.@input = nextInputs
            pipeline.addStage(currentStage)
            currentStage.run()
            return currentStage.context.nextInputs?:currentStage.context.output
        }
        Pipeline.currentUnderConstructionPipeline.joiners << result
        return result
    }
    
    /**
     * Take the output from the given closure and forward
     * all of them to all the stages in the list.
     * This is a special case of multiply below. 
     */
    static Object plus(Closure other, List segments) {
        Pipeline pipeline = Pipeline.currentUnderConstructionPipeline
        Closure mul = multiply("*", segments)
        def plusImplementation =  { input1 ->
            
            def currentStage = new PipelineStage(Pipeline.currentRuntimePipeline.get().createContext(), other)
            Pipeline.currentRuntimePipeline.get().addStage(currentStage)
            currentStage.context.setInput(input1)
            currentStage.run()
            Dependencies.instance.checkFiles(currentStage.context.output)
                    
            // If the stage did not return any outputs then we assume
            // that the inputs to the next stage are the same as the inputs
            // to the previous stage
            def nextInputs = currentStage.context.nextInputs
            if(nextInputs == null)
                nextInputs = currentStage.context.@input
                
            Dependencies.instance.checkFiles(nextInputs)
            
            return mul(nextInputs)
        }
        pipeline.joiners << plusImplementation
        return plusImplementation
    }
    
    static Object multiply(Set objs, List segments) {
        if(!objs) 
            throw new PipelineError("Multiply syntax requires a non-empty list of files or chromosomes, but no entries were in the supplied set")
        
        Pipeline pipeline = Pipeline.currentUnderConstructionPipeline
        
        def multiplyImplementation = { input ->
            
            log.info "multiply on input $input on set " + objs
            
            def currentStage = new PipelineStage(Pipeline.currentRuntimePipeline.get().createContext(), {})
            Pipeline.currentRuntimePipeline.get().addStage(currentStage)
            currentStage.context.setInput(input)
            
            List chrs = []
            chrs.addAll(objs)
            chrs.sort()
            
            // Now we have all our inputs, make a 
            // separate pipeline for each one, and execute each parallel segment
            List<Pipeline> childPipelines = []
            List<Runnable> threads = []
            for(Closure s in segments) {
                log.info "Processing segment ${s.hashCode()}"
                chrs.each { chr ->
                    log.info "Creating pipeline to run on chromosome $chr"
                    Pipeline child = Pipeline.currentRuntimePipeline.get().fork()
                    currentStage.children << child
                    Closure segmentClosure = s
                    threads << {
            
                        try {
                            // First we make a "dummy" stage that contains the inputs
                            // to the next stage as outputs.  This allows later logic
                            // to find these "inputs" correctly when it expects to see
                            // all "inputs" reflected as some output of an earlier stage
                            PipelineContext dummyPriorContext = pipeline.createContext()
                            PipelineStage dummyPriorStage = new PipelineStage(dummyPriorContext,{})
                            dummyPriorContext.stageName = dummyPriorStage.stageName = "Nested pipeline segment: $chr"
                                
                            // If the filterInputs option is set, match input files on the region name
                            def childInputs = input
                            
                            def filterInputs = chr.config?.filterInputs
                            
                            if(filterInputs == "auto") {
                                filterInputs = Utils.box(input).any { it.matches(/.*\.chr[1-9A-Z_]*\..*$/) }
                            }
                            
                            if(filterInputs) {
                                log.info "Filtering child pipeline inputs on name $chr.name"
                                
                                childInputs  = Utils.box(input).grep { i -> (i.indexOf('.' + chr.name + '.')>0) }
                                    
                                // Since the name of the region is already in the file path, it does not need
                                // to be applied again to output files
                                child.nameApplied = true
                                    
                                if(!childInputs) {
                                    println "MSG: Skipping region ${chr.name} because no matching inputs were found"
                                    return
                                }
                            }
                                
                            // Note: must be raw output because otherwise the original inputs (from other folders)
                            // can get redirected to the output folder
                            dummyPriorContext.setRawOutput(childInputs)
                                
                            log.info "Adding dummy prior stage for thread ${Thread.currentThread().id} with outputs : $dummyPriorContext.output"
                            child.addStage(dummyPriorStage)
                            def region = chr.region
                            child.variables += [chr: region]
                            child.variables += [region: region]
                            child.name = chr.name
                            child.runSegment(childInputs, segmentClosure)
                        }
                        catch(Exception e) {
                            log.log(Level.SEVERE,"Pipeline segment in thread " + Thread.currentThread().name + " failed with internal error: " + e.message, e)
                            StackTraceUtils.sanitize(e).printStackTrace()
                            child.failed = true
                        }
                    } as Runnable
                    childPipelines << child
                }
            }
            return runAndWaitFor(currentStage, childPipelines, threads)
        }
        
        log.info "Joiners for pipeline " + pipeline.hashCode() + " = " + pipeline.joiners
        pipeline.joiners << multiplyImplementation
        
        return multiplyImplementation
    }
    
    /**
     * Implements the syntax that allows an input filter to 
     * break inputs into samples and pass to multiple parallel 
     * stages in the form
     * <p>
     * <code>"sample_%_*.txt" * [stage1 + stage2 + stage3]</code>
     */
    static Object multiply(String pattern, List segments) {
        splitOnFiles(pattern,segments,true)
    }
    
    /**
     * Implements the syntax that allows an input filter to 
     * break inputs into samples and pass to multiple parallel 
     * stages in the form
     * <p>
     * <code>"sample_%_*.txt" * [stage1 + stage2 + stage3]</code>
     */
    static Object splitOnFiles(String pattern, List segments, boolean requireMatch) {
        segments = segments.collect { 
            if(it instanceof List) {
                return multiply("*",it)
            }
            else 
                return it
        }
        
        Pipeline pipeline = Pipeline.currentRuntimePipeline.get() ?: Pipeline.currentUnderConstructionPipeline
        
        def multiplyImplementation = { input ->
            
            log.info "multiply on input $input with pattern $pattern"
            
            PipelineStage currentStage = new PipelineStage(pipeline.createContext(), {})
            Pipeline.currentRuntimePipeline.get().addStage(currentStage)
            currentStage.context.setInput(input)
            
            log.info "Created pipeline stage ${currentStage.hashCode()} for parallel block"
            
            // Match the input
            InputSplitter splitter = new InputSplitter()
            Map samples = splitter.split(pattern, input)
            
           
            if(samples.isEmpty() && !requireMatch && pattern == "*")        
                samples["*"] = input
                
            if(samples.isEmpty()) {
                def allInputs = Pipeline.currentRuntimePipeline.get().stages*.context.collect { it.@input}
                for(def inps in allInputs.reverse()) {
                    log.info "Checking input split match on $inps"
                    samples = splitter.split(pattern, inps)
                    if(!samples.isEmpty()) {
                        log.info "Found input split match for pattern $pattern on $inps"
                        break
                    }
                }
            }
            
            if(samples.isEmpty()) 
                if(input)
                    throw new PipelineError("The pattern provided '$pattern' did not match any of the files provided as input $input")
                else
                    throw new PatternInputMissingError("An input pattern was specified '$pattern' but no inputs were given when Bpipe was run.")
					
            // Now we have all our samples, make a 
            // separate pipeline for each one, and for each parallel stage
            List<Pipeline> childPipelines = []
            List<Runnable> threads = []
            for(Closure s in segments) {
                log.info "Processing segment ${s.hashCode()}"
                samples.each { id, files ->
                    
                    log.info "Creating pipeline to run parallel segment $id with files $files"
                   
                    Pipeline child = Pipeline.currentRuntimePipeline.get().fork()
                    currentStage.children << child
                    Closure segmentClosure = s
                    threads << {
                        try {
                            // First we make a "dummy" stage that contains the inputs
                            // to the next stage as outputs.  This allows later logic
                            // to find these "inputs" correctly when it expects to see
                            // all "inputs" reflected as some output of an earlier stage
                            PipelineContext dummyPriorContext = pipeline.createContext()
                            PipelineStage dummyPriorStage = new PipelineStage(dummyPriorContext,{})
                                
                            // Need to set this without redirection to the output folder because otherwise
                            dummyPriorContext.setRawOutput(files)
                            dummyPriorContext.@input = files
                                
                            log.info "Adding dummy prior stage for thread ${Thread.currentThread().id} with outputs : $dummyPriorContext.output"
                            child.addStage(dummyPriorStage)
                            child.name = id
                            child.nameApplied = true
                            child.runSegment(files, segmentClosure)
                        }
                        catch(Exception e) {
                            log.log(Level.SEVERE,"Pipeline segment in thread " + Thread.currentThread().name + " failed with internal error: " + e.message, e)
                            StackTraceUtils.sanitize(e).printStackTrace()
                            child.failExceptions << e
                            child.failed = true
                        }
                    } as Runnable
                    childPipelines << child
                }
            }
            return runAndWaitFor(currentStage, childPipelines, threads)
        }
        
        log.info "Joiners for pipeline " + pipeline.hashCode() + " = " + pipeline.joiners
        pipeline.joiners << multiplyImplementation
        
        return multiplyImplementation
    }
    
    static runAndWaitFor(PipelineStage currentStage, List<Pipeline> pipelines, List<Runnable> threads) {
            // Start all the threads
            Concurrency.instance.execute(threads)
            
            if(pipelines.any { it.failed }) {
                def messages = summarizeErrors(pipelines)
                
                Pipeline current = Pipeline.currentRuntimePipeline.get()
                for(Pipeline p in pipelines.grep { it.failed }) {
                    current.failExceptions.addAll(p.failExceptions)
                }
                current.failReason = messages
                
                throw new PipelineError("One or more parallel stages aborted. The following messages were reported: \n\n" + messages)
            }
            
            def nextInputs = []
            
        
            Pipeline parent = Pipeline.currentRuntimePipeline.get()
            
            mergeChildStagesToParent(parent, pipelines)

            def finalOutputs = parent.stages[-1].context.@output
            return finalOutputs
    }
    
    /**
     * Merge 'like' stages from the given pipelines together so that they appear 
     * as single stages and insert them into the current pipeline.
     * Add all 'unlike' stages to the parent separately.
     * 
     * To explain why merging of 'like' stages is useful, consider the pipeline below:
     *
     *       ---- B --- C ----
     * A -- |                 | ----- D
     *       ---- B --- C ----
     *
     * We want Stage D to see a "virtual" flat pipeline where outputs of the B stages
     * and C stages are merged down and the pipeline looks like A -- B -- C -- D.
     * Consider alternatively the following pipeline:
     * 
     *       ---- B --- C ----
     * A -- |                 | ----- F
     *       ---- D --- E ----
     *
     * We want Stage F to see a different "virtual" flat pipeline 
     * looking like this:
     * 
     *   A -- B -- D -- C -- E -- F
     */
    static mergeChildStagesToParent(Pipeline parent, List<Pipeline> pipelines) {
        // Get the output stages without the joiners
        List<List<PipelineStage>> stagesList = pipelines.collect { c -> c.stages.grep { !it.joiner && !(it in parent.stages) && it.stageName != "Unknown" } }
        
        int maxLen = stagesList*.size().max()
        log.info "Maximum child stage list length = $maxLen"
        
        // Fill in shorter stages with nulls
        stagesList = stagesList.collect { it + ([null] * (maxLen - it.size())) }
        
        def transposed = stagesList.transpose()
        transposed.eachWithIndex { List<PipelineStage> stagesAtIndex, int i ->
            log.info "Grouping stages ${stagesAtIndex*.stageName} for merging"
            
            if(stagesAtIndex.size() == 0)
                throw new IllegalStateException("Encountered pipeline segment with zero parallel stages?")
                
            Map<String,List<PipelineStage>> grouped = stagesAtIndex.groupBy { it?.stageName }
            grouped.each { stageName, stages ->
                  
                if(!stageName || !stages)
                    return
                  
                log.info "Parallel segment $i contains of identical ${stageName} stages - Merging outputs to single stage"
                  
                // Create a merged stage
                PipelineContext mergedContext = new PipelineContext(null, parent.stages, stages[0].context.pipelineJoiners, stages[0].context.branch)
                def mergedOutputs = stages.collect { s ->
                    Utils.box(s.context.nextInputs ?: s.context.@output)
                }.sum()
                  
                log.info "Merged outputs are $mergedOutputs"
                mergedContext.setRawOutput(mergedOutputs)
                  
                PipelineStage mergedStage = new PipelineStage(mergedContext, stages[0].body)
                mergedStage.stageName = stages[0].stageName
                parent.stages.add(mergedStage)
            }
        }
        
        // Finally add a merged stage that has all the outputs from the last stages
        List<PipelineStage> finalStages = transposed[-1]
        PipelineContext mergedContext = new PipelineContext(null, parent.stages, finalStages[0].context.pipelineJoiners, finalStages[0].context.branch)
        def mergedOutputs = finalStages.collect { s ->
            Utils.box(s.context.nextInputs ?: s.context.@output)
        }.sum().unique()
        log.info "Last merged outputs are $mergedOutputs"
        mergedContext.setRawOutput(mergedOutputs)
        PipelineStage mergedStage = new PipelineStage(mergedContext, finalStages[0].body)
        parent.stages.add(mergedStage)
    }
    
    static String summarizeErrors(List<Pipeline> pipelines) {
        pipelines.collect { 
                    if(it.failReason && it.failReason!="Unknown") 
                        return it.failReason
                    else
                    if(it.failExceptions)
                        return it.failExceptions*.message.join('\n')
                    else
                    return null
        }.grep { it }.flatten().unique().join('\n') 
    }
    
    static void addStages(Binding binding) {
        binding.variables.each { 
            if(it.value instanceof Closure) {
                log.info("Found closure variable ${it.key}")
                if(!closureNames.containsKey(it.value))
                    closureNames[it.value] = it.key
            }
        }
    }
    
    static String getPrefix(String value) {
        return value.replaceAll('\\.[^\\.]*?$', '')    
    }
    
    /**
     * Add all properties of type Closure belonging to the class of the given 
     * object as known (named) pipeline stages.
     * 
     * @param host
     */
    static void addStages(def host) {
        // Let's introspect the clazz to see what closure attributes it has
        log.info("Adding stages from $host")
        host.metaClass.properties.each { MetaBeanProperty p ->
            try {
                def x = p.getProperty(host)
                if(x instanceof Closure) {
                    log.info("Found pipeline stage ${p.name}")
                    PipelineCategory.closureNames[x] = p.name
                }
            }
            catch(Exception e) {
                // println "Ignoring $p ($e)"
            }
        }
    }
}
