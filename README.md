Note: This repository is not relevant for the latest (>= 8) versions of Gradle - these have improved cache control settings.

# gradle-cache-cleaner
Reads the Gradle journal in `~/.gradle/caches/journal-1` to determine which files have not been used recently, and deletes them. 

Bugs, feature suggestions and help requests can be filed with the [issue-tracker].

### Help wanted! 
Do you know a better way to clear the Gradle cache, let me know. 

 * Filesystem `atime` does not work (because many filesystems are mounted without this active).

## Obtain
The project is implemented in Java and built using [Maven]. The project is available on the central Maven repository.

It assumes the classpath contains a Gradle distribution.

## Usage
```
com.github.skjolber.gradle.cleaner.Runner timestamp
```

where `timestamp` is the UTC time in milliseconds. Files not accessed since the timestamp will be deleted. 

Note that this process is not perfect, but works because of Gradles lenient nature.

## Compatibility

Tested with Gradle 6.x and 7.1.x

## License
[Apache 2.0]

# History

 - 1.0.0: Initial release
 
[Apache 2.0]:           http://www.apache.org/licenses/LICENSE-2.0.html
[issue-tracker]:        https://github.com/skjolber/gradle-cache-cleaner/issues
[Maven]:                http://maven.apache.org/

 
