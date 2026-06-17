---
name: hotspot-jmh-benchmarks
description: Build, run, and analyze JMH microbenchmarks from the OpenJDK source tree — includes assembly dumping from JMH context
whenToUse: When running JMH benchmarks from the OpenJDK build, comparing benchmark results between JDK versions, or dumping assembly from JMH-compiled methods
---

# OpenJDK JMH Benchmarks

## Building Microbenchmarks

```bash
# Download JMH jars
mkdir -p ~/jmh && cd ~/jmh
curl -sLO https://repo1.maven.org/maven2/org/openjdk/jmh/jmh-core/1.37/jmh-core-1.37.jar
curl -sLO https://repo1.maven.org/maven2/org/openjdk/jmh/jmh-generator-annprocess/1.37/jmh-generator-annprocess-1.37.jar
curl -sLO https://repo1.maven.org/maven2/net/sf/jopt-simple/jopt-simple/5.0.4/jopt-simple-5.0.4.jar
curl -sLO https://repo1.maven.org/maven2/org/apache/commons/commons-math3/3.6.1/commons-math3-3.6.1.jar

# Configure with JMH
bash configure --with-debug-level=release --with-jmh=~/jmh

# Build benchmarks
make CONF=linux-x86_64-server-release build-microbenchmark
```

The benchmark jar is at:
`build/<conf>/images/test/micro/benchmarks.jar`

## Running Benchmarks

```bash
JAVA=build/linux-x86_64-server-release/jdk/bin/java
JAR=build/linux-x86_64-server-release/images/test/micro/benchmarks.jar

# Run specific benchmark
$JAVA -jar $JAR "BenchmarkClass.method" -p SIZE=2048 -f 1 -wi 5 -i 3

# Run with regex filter
$JAVA -jar $JAR "VectorReduction2.NoSuperword.(shortXorBig|byteXorBig)" \
    -p SIZE=2048 -p seed=0 -f 1 -wi 5 -i 3 -w 2s -r 2s
```

### Common Options

| Option | Meaning |
|--------|---------|
| `-f N` | Number of forks (separate JVM per fork) |
| `-wi N` | Warmup iterations |
| `-i N` | Measurement iterations |
| `-w Ns` | Warmup time per iteration |
| `-r Ns` | Measurement time per iteration |
| `-p KEY=VAL` | Parameter value |

## Dumping Assembly from JMH

Use a **fastdebug** JDK for the forked JVM to get disassembly:

```bash
JAVA=build/linux-x86_64-server-fastdebug/jdk/bin/java
JAR=build/linux-x86_64-server-fastdebug/images/test/micro/benchmarks.jar

$JAVA -jar $JAR "BenchmarkClass.method" \
    -p SIZE=2048 -f 1 -wi 3 -i 1 -w 1s -r 1s \
    -jvmArgs "-XX:CompileCommand=PrintAssembly,*BenchmarkClass::method"
```

`-jvmArgs` passes flags to the **forked** JVM (where benchmarks execute).

### Dumping Ideal IR from JMH

```bash
$JAVA -jar $JAR "BenchmarkClass.method" \
    -jvmArgs "-XX:+PrintIdeal -XX:CompileCommand=compileonly,*BenchmarkClass::method"
```

### Dumping Scheduling Trace from JMH

```bash
$JAVA -jar $JAR "BenchmarkClass.method" \
    -jvmArgs "-XX:+TraceOptoPipelining -XX:CompileCommand=compileonly,*BenchmarkClass::method"
```

## JMH Lock Conflict

If two JMH runs overlap, the second fails with:
```
ERROR: Another JMH instance might be running. Unable to acquire the JMH lock
```

Wait for the first to finish, or add `-Djmh.ignoreLock=true`.

## Comparing Baseline vs Patch

```bash
# Build both
BASE_JAVA=../jdk-baseline/build/.../jdk/bin/java
BASE_JAR=../jdk-baseline/build/.../images/test/micro/benchmarks.jar
PATCH_JAVA=build/.../jdk/bin/java
PATCH_JAR=build/.../images/test/micro/benchmarks.jar

# Run baseline first, then patch (avoid lock conflict)
echo "=== BASELINE ===" && $BASE_JAVA -jar $BASE_JAR "..." -f 1 -wi 5 -i 3
echo "=== PATCH ==="    && $PATCH_JAVA -jar $PATCH_JAR "..." -f 1 -wi 5 -i 3
```

Use **release** builds for accurate timing, **fastdebug** for assembly dumps.

## Benchmark Source Location

OpenJDK microbenchmarks are in:
```
test/micro/org/openjdk/bench/vm/compiler/
```
