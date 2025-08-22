package sh.siava.pixelxpert.annotations;

public class ModPackData {
	public Class<?> clazz;
	public String targetPackage;
	public boolean targetsMainProcess;
	public boolean targetsChildProcess;
	public String childProcessName;

	public ModPackData(Class<?> clazz, String targetPackage, boolean targetsMainProcess, boolean targetsChildProcess, String childProcessName) {
		this.clazz = clazz;
		this.targetPackage = targetPackage;
		this.targetsMainProcess = targetsMainProcess;
		this.targetsChildProcess = targetsChildProcess;
		this.childProcessName = childProcessName;
	}
}