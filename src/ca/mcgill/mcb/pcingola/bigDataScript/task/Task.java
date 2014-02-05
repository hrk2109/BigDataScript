package ca.mcgill.mcb.pcingola.bigDataScript.task;

import java.io.File;
import java.io.IOException;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;

import ca.mcgill.mcb.pcingola.bigDataScript.cluster.host.HostResources;
import ca.mcgill.mcb.pcingola.bigDataScript.lang.Type;
import ca.mcgill.mcb.pcingola.bigDataScript.lang.TypeList;
import ca.mcgill.mcb.pcingola.bigDataScript.serialize.BigDataScriptSerialize;
import ca.mcgill.mcb.pcingola.bigDataScript.serialize.BigDataScriptSerializer;
import ca.mcgill.mcb.pcingola.bigDataScript.util.Gpr;
import ca.mcgill.mcb.pcingola.bigDataScript.util.Timer;

/**
 * A task to be executed by an Executioner
 * 
 * @author pcingola
 */
public class Task implements BigDataScriptSerialize {

	public enum DependencyState {
		OK // All dependencies have successfully finished execution
		, WAIT // Still waiting for a dependency to finish
		, ERROR // One or more dependency failed
	}

	public enum TaskState {
		NONE // Task created, nothing happened so far
		, STARTED // Process started (or queued for execution)
		, START_FAILED // Process failed to start (or failed to queue)
		, RUNNING // Running OK
		, ERROR // Filed while running
		, ERROR_TIMEOUT // Filed due to timeout
		, KILLED // Task was killed  
		, FINISHED // Finished OK  
		;

		public static TaskState exitCode2taskState(int exitCode) {
			switch (exitCode) {
			case EXITCODE_OK:
				return FINISHED;

			case EXITCODE_ERROR:
				return ERROR;

			case EXITCODE_TIMEOUT:
				return ERROR_TIMEOUT;

			case EXITCODE_KILLED:
				return KILLED;

			default:
				return ERROR;
			}

		}
	}

	// TODO: This should be a variable (SHEBANG?)
	public static final String SHE_BANG = "#!/bin/sh -e\n\n"; // Use '-e' so that shell script stops after first error

	// Exit codes (see bds.go)
	public static final int EXITCODE_OK = 0;
	public static final int EXITCODE_ERROR = 1;
	public static final int EXITCODE_TIMEOUT = 2;
	public static final int EXITCODE_KILLED = 3;

	protected boolean verbose, debug;
	protected boolean canFail; // Allow execution to fail
	protected int bdsLineNum; // Program's line number that created this task (used for reporting errors)
	protected int exitValue; // Exit (error) code
	protected String id; // Task ID
	protected String bdsFileName; // Program file that created this task (used for reporting errors)
	protected String pid; // PID (if any)
	protected String programFileName; // Program file name
	protected String programTxt; // Program's text (program's code)
	protected String node; // Preferred execution node (or hostname)
	protected String queue; // Preferred execution queue
	protected String stdoutFile, stderrFile, exitCodeFile; // STDOUT, STDERR & exit code Files
	protected String errorMsg;
	protected Date runningStartTime, runningEndTime;
	protected TaskState taskState;
	protected HostResources resources; // Resources to be consumes when executing this task
	protected List<String> inputFiles; // Input files generated by this task. TODO Serialize this!
	protected List<String> outputFiles; // Output files generated by this task. TODO Serialize this!
	protected String checkOutputFiles; // Errors that pop-up when checking output files
	protected List<Task> dependency; // Task that need to finish before this one is executed

	public Task() {
		resources = new HostResources();
		reset();
	}

	public Task(String id, String programFileName, String programTxt, String bdsFileName, int bdsLineNum) {
		this.id = id;
		this.programFileName = programFileName;
		this.programTxt = programTxt;
		this.bdsFileName = bdsFileName;
		this.bdsLineNum = bdsLineNum;
		resources = new HostResources();
		reset();
	}

	/**
	 * Add a dependency task (i.e. taskDep must finish before this task starts)
	 * @param taskDep
	 */
	public void addDependency(Task taskDep) {
		if (dependency == null) dependency = new LinkedList<Task>();
		dependency.add(taskDep);
	}

	/**
	 * Can this task run?
	 * I.e.: It has not been started yet and all dependencies are satisfied
	 * @return true if we are ready to run this task
	 */
	public boolean canRun() {
		return taskState == TaskState.NONE;
	}

	/**
	 * Check if output files are OK
	 * @return true if OK, false there is an error (output file does not exist or has zero length)
	 */
	protected String checkOutputFiles() {
		if (checkOutputFiles != null) return checkOutputFiles;
		if (!isStateFinished() || outputFiles == null) return ""; // Nothing to check

		checkOutputFiles = "";
		for (String fileName : outputFiles) {
			File file = new File(fileName);
			if (!file.exists()) checkOutputFiles += "Error: Output file '" + fileName + "' does not exist";
			else if (file.length() <= 0) checkOutputFiles += "Error: Output file '" + fileName + "' has zero length";
		}

		if (verbose && !checkOutputFiles.isEmpty()) Timer.showStdErr(checkOutputFiles);
		return checkOutputFiles;
	}

	/**
	 * Create a program file
	 */
	public void createProgramFile() {
		if (debug) Gpr.debug("Saving file '" + programFileName + "'");

		// Create dir
		try {
			File dir = new File(programFileName);
			dir = dir.getCanonicalFile().getParentFile();
			if (dir != null) dir.mkdirs();
		} catch (IOException e) {
			// Nothing to do
		}

		// Create file
		Gpr.toFile(programFileName, SHE_BANG + programTxt);
		(new File(programFileName)).setExecutable(true); // Allow execution 

		// Set default file names
		String base = Gpr.removeExt(programFileName);
		if (stdoutFile == null) stdoutFile = base + ".stdout";
		if (stderrFile == null) stderrFile = base + ".stderr";
		if (exitCodeFile == null) exitCodeFile = base + ".exitCode";
	}

	/**
	 * Remove tmp files on exit
	 */
	public void deleteOnExit() {
		if (stdoutFile != null) (new File(stdoutFile)).deleteOnExit();
		if (stderrFile != null) (new File(stderrFile)).deleteOnExit();
		if (exitCodeFile != null) (new File(exitCodeFile)).deleteOnExit();
		if (programFileName != null) (new File(programFileName)).deleteOnExit();
	}

	/**
	 * Mark output files to be deleted on exit
	 */
	public void deleteOutputFilesOnExit() {
		if (outputFiles == null) return; // Nothing to check

		for (String fileName : outputFiles) {
			File file = new File(fileName);
			if (file.exists()) file.deleteOnExit();
		}
	}

	public DependencyState dependencyState() {
		HashSet<Task> tasks = new HashSet<Task>();
		return dependencyState(tasks);
	}

	/**
	 * Are dependencies satisfied? (i.e. can we execute this task?)
	 * @return true if all dependencies are satisfied
	 */
	protected synchronized DependencyState dependencyState(Set<Task> tasksVisited) {
		// Task already finished?
		if (isDone()) {
			if (isCanFail() || isDoneOk()) return DependencyState.OK;
			return DependencyState.ERROR;
		}
		if (isStarted()) return DependencyState.WAIT; // Already started but not finished? => Then you should wait;
		if (dependency == null) return DependencyState.OK; // No dependencies? => we are ready to execute

		// TODO: How do we deal with circular dependency 
		if (tasksVisited.contains(this)) throw new RuntimeException("Circular dependency on task:" + this);
		tasksVisited.add(this);

		// Check that all dependencies are OK
		for (Task task : dependency) {
			// Analyze dependent task
			DependencyState dep = task.dependencyState(tasksVisited);
			if (dep != DependencyState.OK) return dep; // Propagate non-OK states (i.e. error or wait)
			if (!task.isDone()) return DependencyState.WAIT; // Dependency OK, but not finished? => Wait for it
		}

		// Only if all dependent tasks are OK, we can say that we are ready
		return DependencyState.OK;
	}

	/**
	 * Elapsed number of seconds this task has been executing
	 * @return
	 */
	public int elapsedSecs() {
		if (runningStartTime == null) return -1; // Not started?
		if (getResources() == null) return -1; // No resources?

		// Calculate elapsed processing time
		long end = (runningEndTime != null ? runningEndTime : new Date()).getTime();
		long start = runningStartTime.getTime();
		int elapsedSecs = (int) ((end - start) / 1000);
		return elapsedSecs;
	}

	public String getBdsFileName() {
		return bdsFileName;
	}

	public int getBdsLineNum() {
		return bdsLineNum;
	}

	public List<Task> getDependency() {
		return dependency;
	}

	public String getExitCodeFile() {
		return exitCodeFile;
	}

	public synchronized int getExitValue() {
		if (!checkOutputFiles().isEmpty()) return 1; // Any output file failed?
		return exitValue;
	}

	public String getId() {
		return id;
	}

	public List<String> getInputFiles() {
		return inputFiles;
	}

	public String getNode() {
		return node;
	}

	public List<String> getOutputFiles() {
		return outputFiles;
	}

	public synchronized String getPid() {
		return pid;
	}

	public String getProgramFileName() {
		return programFileName;
	}

	public String getQueue() {
		return queue;
	}

	public HostResources getResources() {
		return resources;
	}

	public String getStderrFile() {
		return stderrFile;
	}

	public String getStdoutFile() {
		return stdoutFile;
	}

	public TaskState getTaskState() {
		return taskState;
	}

	public boolean isCanFail() {
		return canFail;
	}

	/**
	 * Has this task finished? Either finished OK or finished because of errors.
	 * @return
	 */
	public synchronized boolean isDone() {
		return isError() || isStateFinished();
	}

	/**
	 * Has this task been executed successfully?
	 * The task has finished, exit code is zero and all output files have been created
	 * 
	 * @return
	 */
	public synchronized boolean isDoneOk() {
		return isStateFinished() && (exitValue == 0) && checkOutputFiles().isEmpty();
	}

	/**
	 * Is this task in any error or killed state?
	 * @return
	 */
	public synchronized boolean isError() {
		return (taskState == TaskState.START_FAILED) //
				|| (taskState == TaskState.ERROR) //
				|| (taskState == TaskState.ERROR_TIMEOUT) //
				|| (taskState == TaskState.KILLED) //
		;
	}

	/**
	 * Has this task been executed and failed?
	 * 
	 * This is true if:
	 * 		- The task has finished execution and it is in an error state 
	 * 		- OR exitValue is non-zero 
	 * 		- OR any of the output files was not created
	 * 
	 * @return
	 */
	public synchronized boolean isFailed() {
		return isError() || (exitValue != 0) || !checkOutputFiles().isEmpty();
	}

	/**
	 * Has the task been started?
	 * @return
	 */
	public synchronized boolean isStarted() {
		return taskState != TaskState.NONE;
	}

	public synchronized boolean isStateFinished() {
		return taskState == TaskState.FINISHED;
	}

	public synchronized boolean isStateRunning() {
		return taskState == TaskState.RUNNING;
	}

	public synchronized boolean isStateStarted() {
		return taskState == TaskState.STARTED;
	}

	/**
	 * Has this task run out of time?
	 * @return
	 */
	public boolean isTimedOut() {
		int elapsedSecs = elapsedSecs();
		if (elapsedSecs < 0) return false;

		// Run out of time?
		int timeout = (int) getResources().getTimeout();
		return elapsedSecs > timeout;
	}

	/**
	 * Reset parameters and allow a task to be re-executed
	 */
	public void reset() {
		taskState = TaskState.NONE;
		exitValue = 0;
	}

	@SuppressWarnings("unchecked")
	@Override
	public void serializeParse(BigDataScriptSerializer serializer) {
		// Note that "Task classname" field has been consumed at this point
		id = serializer.getNextField();
		bdsFileName = serializer.getNextFieldString();
		bdsLineNum = (int) serializer.getNextFieldInt();
		canFail = serializer.getNextFieldBool();
		taskState = TaskState.valueOf(serializer.getNextFieldString());
		exitValue = (int) serializer.getNextFieldInt();
		node = serializer.getNextField();
		queue = serializer.getNextField();
		programFileName = serializer.getNextFieldString();
		programTxt = serializer.getNextFieldString();
		stdoutFile = serializer.getNextFieldString();
		stderrFile = serializer.getNextFieldString();
		exitCodeFile = serializer.getNextFieldString();

		inputFiles = serializer.getNextFieldList(TypeList.get(Type.STRING));
		outputFiles = serializer.getNextFieldList(TypeList.get(Type.STRING));

		resources = new HostResources();
		resources.serializeParse(serializer);
	}

	@Override
	public String serializeSave(BigDataScriptSerializer serializer) {
		return getClass().getSimpleName() //
				+ "\t" + id // 
				+ "\t" + serializer.serializeSaveValue(bdsFileName) //
				+ "\t" + bdsLineNum //
				+ "\t" + canFail // 
				+ "\t" + taskState // 
				+ "\t" + exitValue // 
				+ "\t" + node // 
				+ "\t" + queue // 
				+ "\t" + serializer.serializeSaveValue(programFileName) //
				+ "\t" + serializer.serializeSaveValue(programTxt) //
				+ "\t" + serializer.serializeSaveValue(stdoutFile) //
				+ "\t" + serializer.serializeSaveValue(stderrFile) //
				+ "\t" + serializer.serializeSaveValue(exitCodeFile) //
				+ "\t" + serializer.serializeSaveValue(inputFiles) //
				+ "\t" + serializer.serializeSaveValue(outputFiles) //
				+ "\t" + resources.serializeSave(serializer) //
				+ "\n";
	}

	public void setCanFail(boolean canFail) {
		this.canFail = canFail;
	}

	public void setDebug(boolean debug) {
		this.debug = debug;
	}

	public void setErrorMsg(String errorMsg) {
		this.errorMsg = errorMsg;
	}

	public synchronized void setExitValue(int exitValue) {
		this.exitValue = exitValue;
	}

	public void setInputFiles(List<String> inputFiles) {
		this.inputFiles = inputFiles;
	}

	public void setNode(String node) {
		this.node = node;
	}

	public void setOutputFiles(List<String> outputFiles) {
		this.outputFiles = outputFiles;
	}

	public void setPid(String pid) {
		this.pid = pid;
	}

	public void setQueue(String queue) {
		this.queue = queue;
	}

	private void setState(TaskState taskState) {
		this.taskState = taskState;
	}

	public void setVerbose(boolean verbose) {
		this.verbose = verbose;
	}

	/**
	 * Change state: Make sure state changes are valid
	 * @param newState
	 */
	public synchronized void state(TaskState newState) {
		if (newState == null) throw new RuntimeException("Cannot change to 'null' state.\n" + this);
		if (newState == taskState) return; // Nothing to do

		switch (newState) {
		case STARTED:
			if (taskState == TaskState.NONE) setState(newState);
			else throw new RuntimeException("Task: Cannot jump from state '" + taskState + "' to state '" + newState + "'\n" + this);
			break;

		case START_FAILED:
			if (taskState == TaskState.NONE) {
				setState(newState);
				runningStartTime = runningEndTime = new Date();
			} else if (taskState == TaskState.KILLED) ; // OK, don't change state
			else throw new RuntimeException("Task: Cannot jump from state '" + taskState + "' to state '" + newState + "'\n" + this);
			break;

		case RUNNING:
			if (taskState == TaskState.STARTED) {
				setState(newState);
				runningStartTime = new Date();
			} else throw new RuntimeException("Task: Cannot jump from state '" + taskState + "' to state '" + newState + "'\n" + this);
			break;

		case FINISHED:
		case ERROR:
		case ERROR_TIMEOUT:
			if (taskState == TaskState.RUNNING) {
				setState(newState);
				runningEndTime = new Date();
			} else throw new RuntimeException("Task: Cannot jump from state '" + taskState + "' to state '" + newState + "'\n" + this);
			break;

		case KILLED:
			if ((taskState == TaskState.RUNNING) // A task can be killed while running...
					|| (taskState == TaskState.STARTED) // or right after it started
					|| (taskState == TaskState.NONE) // or even if it was not started
			) {
				setState(newState);
				runningEndTime = new Date();
			} else throw new RuntimeException("Task: Cannot jump from state '" + taskState + "' to state '" + newState + "'\n" + this);
			break;

		default:
			// Ignore other state changes
			throw new RuntimeException("Unimplemented state: '" + newState + "'");
		}
	}

	@Override
	public String toString() {
		return toString(verbose, debug);
	}

	public String toString(boolean verbose) {
		return toString(verbose, debug);
	}

	public String toString(boolean verbose, boolean showCode) {
		StringBuilder sb = new StringBuilder();

		if (verbose) {
			sb.append("\tProgram & line     : '" + bdsFileName + "', line " + bdsLineNum + "\n");
			sb.append("\tTask ID            : '" + id + "'\n");
			sb.append("\tTask state         : '" + taskState + "'\n");
			sb.append("\tInput files        : '" + inputFiles + "'\n");
			sb.append("\tOutput files       : '" + outputFiles + "'\n");

			if (dependency != null && !dependency.isEmpty()) {
				sb.append("\tTask dependencies  : ");
				sb.append(" [ ");
				boolean comma = false;
				for (Task t : dependency) {
					sb.append((comma ? ", " : "") + "'" + t.getId() + "'");
					comma = true;
				}
				sb.append(" ]\n");
			}

			sb.append("\tScript file        : '" + programFileName + "'\n");
			if (errorMsg != null) sb.append("\tError message      : '" + errorMsg + "'\n");
			sb.append("\tExit status        : '" + exitValue + "'\n");

			String ch = checkOutputFiles();
			if ((ch != null) && !ch.isEmpty()) sb.append("\tOutput file errors :\n" + Gpr.prependEachLine("\t\t", ch));

			String tailErr = TailFile.tail(stderrFile);
			if ((tailErr != null) && !tailErr.isEmpty()) sb.append("\tStdErr (10 lines)  :\n" + Gpr.prependEachLine("\t\t", tailErr));

			String tailOut = TailFile.tail(stdoutFile);
			if ((tailOut != null) && !tailOut.isEmpty()) sb.append("\tStdOut (10 lines)  :\n" + Gpr.prependEachLine("\t\t", tailOut));

			if (showCode) {
				sb.append("\tTask raw code:\n");
				sb.append("-------------------- Task code: Start --------------------\n");
				sb.append(programTxt + "\n");
				sb.append("-------------------- Task code: End   --------------------\n");
			}

		} else sb.append("'" + bdsFileName + "', line " + bdsLineNum);

		return sb.toString();
	}

}
