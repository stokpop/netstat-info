# netstat info

Produce some netstat -an files for instance as follows:

    netstat -an > netstat.1.info
    sleep 10
    netstat -an > netstat.2.info
    
Now have the netstat-info jar file available:

    curl -s -L -o netstat-info.jar https://github.com/stokpop/netstat-info/releases/download/1.0.0/netstat-info-1.0.0-all.jar

Create a mapper file to map ip's to known names, for instance `netstat.mapper`:
 
     127.0.0.1=localhost
     1.2.3.4=my_laptop
         
Run netstat-info with the files:

    java -jar netstat-info.jar report netstat.1.info netstat.mapper

The command is:

    java -jar netstat-info.jar <report|compare> <netstat-filename> [mapper-filename]

The mapper file is optional.
    
This will show a report like:

    ========= Processing netstat.1.info =========
    
    ==> Listen ports (15)
    ..., 3633, 6395, 6434
    
    === INCOMING ===
    
    ==> Count per state (INCOMING)
    I ESTABLISHED(3633)=1
    I ESTABLISHED(6395)=2
    I ESTABLISHED(6434)=1
    
    ==> Count established per address and port (INCOMING)
    I 192.168.1.125(6395)=1
    I 192.168.1.205(6395)=1
    I localhost(3633)=1
    I localhost(6434)=1
    
    === OUTGOING ===
    
    ==> Count per state (OUTGOING)
    O CLOSE_WAIT(443)=6
    O ESTABLISHED(2553)=1
    O ESTABLISHED(3633)=3
    ...
         
You can also compare two files, and supply the portnumber to compare:

    java -jar netstat-info.jar compare 443 netstat.1.info netstat.2.info netstat.mapper
    
It will output something like:

    ==> compare netstat.1.info and netstat.2.info
    5.11.77.13(443)-my_laptop(52332) ESTABLISHED ==> ESTABLISHED
    16.12.3.1(443)-my_laptop(52212) CLOSE_WAIT ==> CLOSE_WAIT
    ...

# directories

When you supply directories instead of file names, all files in the directory will be walked
and reports and/or compares are generated for all found netstat files. Example:

    java -jar netstat-info.jar report netstat.1.info netstat.2.info netstat.mapper

# generate multiple netstat dump

Use this for 10 dumps that are 50 seconds apart:

    for i in {1..10}; do netstat -an > netstat.$(hostname -s).$(date +%FT%H-%M-%S); sleep 50; done

Note: on linux you can also use `netstat -ant` to only have tcp proto lines in your dumps.
    
# thread-dump analysis

A simple analyzer for jcmd thread dumps is available.

Usage examples:

- Analyze all dumps in the default ./thread-dumps directory and print to stdout:

      java -cp build/libs/netstat-info-1.0.0-all.jar nl.stokpop.ThreadDumpAnalyzer

- Or specify input directory and output txt file:

      java -cp build/libs/netstat-info-1.0.0-all.jar nl.stokpop.ThreadDumpAnalyzer thread-dumps thread-dumps-report.txt

- Filter report to stack frames containing a substring (case-insensitive), and only show matching lines in the group keys:

      java -cp build/libs/netstat-info-1.0.0-all.jar nl.stokpop.ThreadDumpAnalyzer thread-dumps thread-dumps-report.txt --filter ecma

  You can also use -f ecma or --filter=ecma.

It will report per dump:
- number of platform vs virtual threads (after filtering if a filter is applied)
- number of virtual threads without a stacktrace
- top groups of similar threads (by normalized stacktrace)

It also includes a cross-dump section to see if similar thread groups are still alive over time.

# build

To build executable jar:

    ./gradlew clean installShadowDist
    
The jar is here:

    ./build/libs/netstat-info-1.0.0-all.jar
    
         
