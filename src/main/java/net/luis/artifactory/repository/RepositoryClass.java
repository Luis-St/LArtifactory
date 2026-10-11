package net.luis.artifactory.repository;

/**
 * Whether a repository stores its own artifacts, proxies an upstream, or aggregates other repositories.<br>
 * <ul>
 *     <li>{@link #LOCAL}: artifacts are published and stored directly</li>
 *     <li>{@link #REMOTE}: artifacts are fetched from an upstream url and optionally cached (read only)</li>
 *     <li>{@link #VIRTUAL}: reads are resolved across a list of member repositories (read only)</li>
 * </ul>
 */
public enum RepositoryClass {
	LOCAL, REMOTE, VIRTUAL
}
