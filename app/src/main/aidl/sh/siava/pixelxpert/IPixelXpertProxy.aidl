// IPixelXpertProxy.aidl
package sh.siava.pixelxpert;

// Declare any non-default types here with import statements

interface IPixelXpertProxy {
	/**
	 * Demonstrates some basic types that you can use as parameters
	 * and return values in AIDL.
	 */
	String[] runRootCommand(String command);
	Bitmap extractSubject(in Bitmap input, int method);
}