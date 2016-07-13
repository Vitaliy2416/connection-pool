# Application - Connection Pool
* *Author* : [Vitaliy Vydrya](https://vk.com/vitaliy2416)
* *Technologies* : Java SE 7, JDBC
* *Summary* : Connection pool application using Java SE 7 

[Download the code from GitHub](https://github.com/vydrya/connection-pool/archive/master.zip)

## Purpose of this application
Creating connection pool like [apache.org](http://svn.apache.org/viewvc/tomcat/trunk/modules/jdbc-pool/src/main/java/org/apache/tomcat/jdbc/pool/)

* [*version 1.0.*](https://github.com/vydrya/connection-pool/blob/master/org/vydrya/jdbc/pool/version1/ConnectionPool.java) - it is based on the LinkedList
*  [*version 2.0.*](https://github.com/vydrya/connection-pool/blob/master/org/vydrya/jdbc/pool/version2/ConnectionPool.java) - it is based on the BlockingQueue (present version does not work)
