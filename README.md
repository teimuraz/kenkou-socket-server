### Framework and language
Akka v2.15.4 is used to build socket server (Akka TCP)
 
Scala version: 2.12.6
 
### How to run 

#### Run server
`sbt run`

#### Run client
In separate terminal window

`cd client`

`./followermaze.sh`


### Description
The socket server is build with Akka TCP.

Server listens on port 9099 through `UsersClientManager` actor.


Once new user client is connected, `UserClientManager` delegates event from connected user
to `UserClientSocketHandler` actor.

User clients sends only 1 message, and this message is the id of user which connects to the server.
After `UserClientSocketHandler` receives id of the user, it notifies `UsersClientManager`
and `UsersClientManager` registers this new user client / connection.

`UserClientManager` listens for user client's connection close event as well and unregisters appropriate 
client.


Events from event source are handled on port 9090 by `EventSourceManager` actor which forwards them
to `EventSourceHandler`.
`EventSourceHandler` receives events by batches, orders events in batch by sequence number and forwards those ordered events
to `UsersClientManager` actor.

Once received, `UsersClientManager` sends events user clients according to event payload (or ignores them in necessary).







