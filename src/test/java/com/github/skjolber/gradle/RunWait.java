package com.github.skjolber.gradle;

import java.util.Scanner;

public class RunWait {

	public static final void main(String[] args) {
		long timestamp = System.currentTimeMillis();
		
        try (Scanner scanner = new Scanner(System.in)) {
	        scanner.nextLine();
			Runner.main(new String[] {"" + timestamp});
			
			RunNow.main(null);
        }
	}
}
