const net = require('net');
const client = new net.Socket();

client.connect(1664, '127.0.0.1', () => {
    console.log('Connected');
    client.write('{"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"clojure_evaluate_code","arguments":{"code":"(+ 1 2)","namespace":"user","replSessionKey":"joyride","who":"copilot"}}}\n');
});

client.on('data', (data) => {
    console.log('Received: ' + data);
    client.destroy();
});

client.on('close', () => {
    console.log('Connection closed');
});
