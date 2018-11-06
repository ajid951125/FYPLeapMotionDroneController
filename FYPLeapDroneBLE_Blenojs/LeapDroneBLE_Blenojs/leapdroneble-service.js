var util = require('util');
var bleno = require('bleno');
var app_string = require('./app_string.js');
var LeapDroneBLECharacteristic = require('./leapdroneble-characteristic');

var BlenoPrimaryService = bleno.PrimaryService; // BLE service

var LeapDroneBLEService = function() {
    LeapDroneBLEService.super_.call(this, {
        uuid: app_string.serviceUUID,
        characteristics: [new LeapDroneBLECharacteristic()]
    });
}

util.inherits(LeapDroneBLEService, BlenoPrimaryService);

module.exports = LeapDroneBLEService;
