const net = require('net');
const client = new net.Socket();

client.connect(55914, '127.0.0.1', () => {
    console.log('Connected');
    client.write('{"method":"tools/list","params":{},"jsonrpc":"2.0","id":1}\n');
});

client.on('data', (data) => {
    console.log('Received: ' + data);
    client.destroy();
});

client.on('close', () => {
    console.log('Connection closed');
});
