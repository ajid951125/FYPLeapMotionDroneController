var bleno = require('bleno');
var LeapDroneBLEService = require('./leapdroneble-service');
var app_string = require('./app_string.js');

var primaryService = new LeapDroneBLEService();

bleno.on('stateChange', function(state) {
  console.log('BLE: ' + state);
  if (state === 'poweredOn') {
    bleno.startAdvertising('Leap Motion', [primaryService.uuid]);
  } else {
    bleno.stopAdvertising();
  }
});

bleno.on('advertisingStart', function(error) {
  console.log('BLE: start advertising ' + (error ? 'error ' + error : 'success'));
  if (!error) {
    bleno.setServices([primaryService], function(error){
      console.log('BLE: service setting '  + (error ? 'error ' + error : 'success'));
      console.log('BLE: [serviceUUID: ' + app_string.serviceUUID + ']');
      console.log('BLE: [characteristicUUID: ' + app_string.characteristicUUID + ']');
    });
  }
});
