package ca.mcgill.mcb.pcingola.bigDataScript.task;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import ca.mcgill.mcb.pcingola.bigDataScript.lang.Expression;
import ca.mcgill.mcb.pcingola.bigDataScript.util.Timer;

/**
 * Output and Input files (and tasks) that are required for a task to succesfully execute
 *
 * @author pcingola
 */
public class TaskDependency {

	boolean debug;
	protected Expression expresison; // Expression that created this 'TaskDependency' (for logging & debugging pourposes)
	protected List<String> inputFiles; // Input files generated by this task
	protected List<String> outputFiles; // Output files generated by this task
	protected String checkOutputFiles; // Errors that pop-up when checking output files
	protected List<Task> tasks; // Task that need to finish before this one is executed

	public TaskDependency() {
		this(null);
	}

	public TaskDependency(Expression expresison) {
		this.expresison = expresison;
		outputFiles = new ArrayList<String>();
		inputFiles = new ArrayList<String>();
		tasks = new ArrayList<Task>();
	}

	public void add(Task task) {
		tasks.add(task);
	}

	/**
	 * Add all dependencies from 'taskDependency' to this this one
	 */
	public void add(TaskDependency taskDependency) {
		addInput(taskDependency.getInputFiles());
		addOutput(taskDependency.getOutputFiles());
		tasks.addAll(taskDependency.getTasks());
	}

	public void addInput(Collection<String> inputs) {
		for (String in : inputs)
			addInput(in);
	}

	public void addInput(String input) {
		// Is 'input' a task ID?
		Task task = TaskDependecies.get().getTask(input);

		if (task != null) tasks.add(task);
		else inputFiles.add(input); // No taskDI, must be a file
	}

	public void addOutput(Collection<String> outputs) {
		outputFiles.addAll(outputs);
	}

	public void addOutput(String output) {
		outputFiles.add(output);
	}

	/**
	 * Check if output files are OK
	 * @return true if OK, false there is an error (output file does not exist or has zero length)
	 */
	public String checkOutputFiles(Task task) {
		if (checkOutputFiles != null) return checkOutputFiles;
		if (!task.isStateFinished() || outputFiles == null) return ""; // Nothing to check

		checkOutputFiles = "";
		for (String fileName : outputFiles) {
			File file = new File(fileName);
			if (!file.exists()) checkOutputFiles += "Error: Output file '" + fileName + "' does not exist.";
			else if ((!task.isAllowEmpty()) && (file.length() <= 0)) checkOutputFiles += "Error: Output file '" + fileName + "' has zero length.";
		}

		if (task.verbose && !checkOutputFiles.isEmpty()) Timer.showStdErr(checkOutputFiles);
		return checkOutputFiles;
	}

	/**
	 * Mark output files to be deleted on exit
	 */
	public void deleteOutputFilesOnExit() {
		for (String fileName : outputFiles) {
			File file = new File(fileName);
			if (file.exists()) file.deleteOnExit();
		}
	}

	/**
	 * Calculate the result of '<-' operator give two collections files (left hand side and right handside)
	 */
	public boolean depOperator() {
		// Empty dependency is always true
		if (outputFiles.isEmpty() && inputFiles.isEmpty()) return true;

		//---
		// Left hand side
		// Calculate minimum modification time
		//---

		long minModifiedLeft = Long.MAX_VALUE;
		for (String fileName : outputFiles) {
			File file = new File(fileName);

			// Any 'left' file does not exists? => We need to build this dependency
			if (!file.exists()) {
				if (debug && (expresison != null)) expresison.log("Left hand side: file '" + fileName + "' doesn't exist");
				return true;
			}

			if (file.isFile() && file.length() <= 0) {
				if (debug && (expresison != null)) expresison.log("Left hand side: file '" + fileName + "' is empty");
				return true; // File is empty? => We need to build this dependency.
			} else if (file.isDirectory()) {
				// Notice: If it is a directory, we must rebuild if it is empty
				File dirList[] = file.listFiles();
				if ((dirList == null) || dirList.length <= 0) {
					if (debug && (expresison != null)) expresison.log("Left hand side: file '" + fileName + "' is empty");
					return true;
				}
			}

			// Analyze modification time
			long modTime = file.lastModified();
			minModifiedLeft = Math.min(minModifiedLeft, modTime);
			if (debug) expresison.log("Left hand side: file '" + fileName + "' modified on " + modTime + ". Min modification time: " + minModifiedLeft);
		}

		//---
		// Right hand side
		// Calculate maximum modification time
		//---

		long maxModifiedRight = Long.MIN_VALUE;
		for (String fileName : inputFiles) {
			File file = new File(fileName);

			// Is this file scheduled to be modified by a pending task? => Time will change => We'll need to update
			List<Task> taskOutList = TaskDependecies.get().getTasksByOutput(fileName);
			if (taskOutList != null && !taskOutList.isEmpty()) {
				for (Task t : taskOutList) {
					// If the task modifying 'file' is not finished => We'll need to update
					if (!t.isDone()) {
						if (debug) expresison.log("Right hand side: file '" + fileName + "' will be modified by task '" + t.getId() + "' (task state: '" + t.getTaskState() + "')");
						return true;
					}
				}
			}

			if (file.exists()) {
				// Update max time
				long modTime = file.lastModified();
				maxModifiedRight = Math.max(maxModifiedRight, modTime);
				if (debug) expresison.log("Right hand side: file '" + fileName + "' modified on " + modTime + ". Max modification time: " + maxModifiedRight);
			} else {
				// Make sure that we schedule the task if the input file doesn't exits
				// The reason to do this, is that probably the input file was defined
				// by some other task that is pending execution.
				if (debug && (expresison != null)) expresison.log("Right hand side: file '" + fileName + "' doesn't exist");
				return true;
			}
		}

		// Have all 'left' files been modified before 'right' files?
		// I.e. Have all goals been created after the input files?
		boolean ret = (minModifiedLeft < maxModifiedRight);
		if (debug) expresison.log("Modification times, minModifiedLeft (" + minModifiedLeft + ") < maxModifiedRight (" + maxModifiedRight + "): " + ret);
		return ret;
	}

	public List<String> getInputFiles() {
		return inputFiles;
	}

	public List<String> getOutputFiles() {
		return outputFiles;
	}

	public List<Task> getTasks() {
		return tasks;
	}

	public boolean hasTasks() {
		return !tasks.isEmpty();
	}

	boolean isTask(String tid) {
		return TaskDependecies.get().hasTask(tid);
	}

	public void setDebug(boolean debug) {
		this.debug = debug;
	}

	@Override
	public String toString() {
		StringBuilder sb = new StringBuilder();
		sb.append("( ");

		if ((outputFiles != null && !outputFiles.isEmpty()) || (inputFiles != null && !inputFiles.isEmpty())) {

			if (outputFiles != null && !outputFiles.isEmpty()) {
				boolean comma = false;
				for (String f : outputFiles) {
					sb.append((comma ? ", " : "") + "'" + f + "'");
					comma = true;
				}
			}

			sb.append(" <- ");

			if (inputFiles != null && !inputFiles.isEmpty()) {
				boolean comma = false;
				for (String f : inputFiles) {
					sb.append((comma ? ", " : "") + "'" + f + "'");
					comma = true;
				}
			}
		}
		sb.append(" )");

		return sb.toString();
	}

}
