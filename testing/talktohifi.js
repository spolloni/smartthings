console.log('Hello World');

const net = require('net');
  const client = new net.Socket();
  client.connect(23, 192.168.2.24, function() {
    client.write("*Z4ENABLE1");
  });