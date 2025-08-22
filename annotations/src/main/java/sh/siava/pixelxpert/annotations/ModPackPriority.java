package sh.siava.pixelxpert.annotations;

/**
 * If not defined, default priority of 99 will be assumed. modpacks will be loaded in order of priority from small to large
 */
public @interface ModPackPriority {
	int priority();
}
