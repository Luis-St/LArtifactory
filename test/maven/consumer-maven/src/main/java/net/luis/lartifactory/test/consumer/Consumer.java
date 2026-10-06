package net.luis.lartifactory.test.consumer;

import net.luis.lartifactory.test.gradle.GradleLib;
import net.luis.lartifactory.test.maven.MavenLib;

public final class Consumer {

	public static void main(String[] args) {
		System.out.println(MavenLib.message() + " / " + GradleLib.message());
	}
}
