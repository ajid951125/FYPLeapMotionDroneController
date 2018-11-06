var util = require('util');
var Leap = require('leapjs');
var bleno = require('bleno');
var micro = require('microtime');
var log4js = require('log4js');
log4js.configure({
    appenders: { latency_calculate_ble: { type: 'file', filename: 'latency_calculate_ble.log' } },
    categories: { default: { appenders: ['latency_calculate_ble'], level: 'debug' } }
});
  
var logger = log4js.getLogger('latency_calculate_ble'); 
var app_string = require('./app_string.js');

var Characteristic = bleno.Characteristic; // BLE characteristic

var leap_controller, timeout, isFlying, isStopped, isYawing, rotation_speed;
// 0 - no_action
// 1 - takeoff
// 2 - land
// 3 - roll
// 4 - throttle
// 5 - pitch
// 6 - yaw
var actionMap = ["no_action","takeoff","land","roll","throttle","pitch","yaw"];
var current_action = "0,0"; // no_action,speed=0

isStopped = true; 
isFlying = false;       // used to prevent action while drone is hovering
isYawing = false;       // used to prevent action while drone is yawing
rotation_speed = 0.5;   // used for rotation rotation_speed
timeout = 200;          // used for each server publish

var LeapDroneBLECharacteristic = function() {
    LeapDroneBLECharacteristic.super_.call(this, {
        uuid: app_string.characteristicUUID,
        properties: ['notify']
    });
};

util.inherits(LeapDroneBLECharacteristic, Characteristic);

// send data of current_action to subscribed android device using BLE_notification
LeapDroneBLECharacteristic.prototype.onSubscribe = function(maxValueSize, updateValueCallback) {
    console.log("BLE: device subscribed");
    console.log("BLE: notifying");
    logger.debug("start new notification");
    this.intervalId = setInterval(function() {
        updateValueCallback(new Buffer(current_action,'utf8'));
        // This is solely to log time in microsecond to calculate latency of this system
        var temp = current_action.split(",");
        var d = new Date();
        logger.debug(d + 'start action->' + actionMap[temp[0]] + ', time(ms): ' + micro.now());
    }, timeout); // time is in millisec
};

LeapDroneBLECharacteristic.prototype.onUnsubscribe = function() {
    console.log("BLE: device unsubscribed");
    if (this.intervalId) {
        clearInterval(this.intervalId);
        this.intervalId = null;
    }
    logger.debug("stopped notification");
};

// initialization of leap motion device
leap_controller = new Leap.Controller({enableGestures: true});
leap_controller.on('ready', function() {
    console.log("LEAP: ready");
});
leap_controller.on('connect', function() {
    console.log("LEAP: connect");
});
leap_controller.on('disconnect', function() {
    console.log("LEAP: disconnect");
});
leap_controller.on('deviceStreaming', function() {
    console.log("LEAP: resume tracking");
});
leap_controller.on('deviceStopped', function() {
    console.log("LEAP: pause tracking");
});
leap_controller.on('frame', function (data) {
    leap_main(data);
});
leap_controller.connect();

// get each frame contain hand data and define certain gesture for certain action
// code of leap motion start frome leap_main
var leap_main = function (frame) {
    gesture_handler(frame);  // function for handling takeoff, landing and yaw
    hand_handler(frame);     // function for handling all other actions [roll,throttle,pitch]
}

var perform_takeoff = function () {
    current_action = updateAction('takeoff',0);
    //console.log("takeoff!");
    setTimeout(function (){
        isFlying = true;
    }, 300);
    this.interval1 = setInterval(function() { //debug
        var temp = current_action.split(",");
        console.log(actionMap[temp[0]]);
    }, timeout);
}

var perform_landing = function () {
    isFlying = false;
    isStopped = true;
    current_action = updateAction('land',0);
    //console.log("land!");
    if (this.interval1) { //debug
        var temp = current_action.split(",");
        console.log(actionMap[temp[0]]);
        clearInterval(this.interval1);
        this.interval1 = null;
    }
}

// for rotating drone yaw_clockwise or yaw_counter_clockwise {YAW}
var perform_yaw = function(yaw_direction) {
    if (yaw_direction === 0){ // yaw_clockwise
        current_action = updateAction('yaw',rotation_speed);
        isYawing = true;
        //console.log("yaw clockwise");
        setTimeout(function (){
            if(isFlying){
                isYawing = false;
            }
        }, timeout*2);
        return;
    }
    if (yaw_direction === 1){ // yaw_counter_clockwise
        current_action = updateAction('yaw',(-1 * rotation_speed));
        isYawing = true;
        //console.log("yaw counter clockwise");
        setTimeout(function (){
            if(isFlying){
                isYawing = false;
            }
        }, timeout*2);
        return;
    }
}

// handle [takeoff, land, yaw] gesture
var gesture_handler = function (frame) {
    var hands = frame.hands // leap detects all hands in field of vision
    var gestures = frame.gestures;

    if (gestures && gestures.length > 0 && hands.length === 1) {
        isStopped = false;
        for( var i = 0; i < gestures.length; i++ ) {
            var gesture = gestures[i];
            if (gesture.type === 'circle' && gesture.state === 'start' && isFlying) {
                gesture.pointable = frame.pointable(gesture.pointableIds[0]);
                direction_vector = gesture.pointable.direction;
                if(direction_vector) { // if direction vector is not [0,0,0]
                    // normalize the gesture vector
                    var normal_vector = gesture.normal;
                    // check based on angle between normal and direction vector
                    clockwisely = Leap.vec3.dot(direction_vector, normal_vector) > 0;
                    if(clockwisely) {
                        return perform_yaw(0); // yaw_clockwise
                    }
                    else {
                        return perform_yaw(1); // yaw_counter_clockwise
                    }
                }
            } 
            else {
                var extendedFinger = getExtendedFingers(frame); // return array of extended finger
                var expected_extendedFinger = [1,1,1,0,0]; // thumb, index, middle
                // go into below statement only if meet the exprected type of finger
                if ( gesture.type === 'keyTap' && extendedFinger.toString() === expected_extendedFinger.toString()) {
                    if (isFlying) {
                        perform_landing();
                    } 
                    else {
                        perform_takeoff();
                    }
                }
            }
        }
    }
};

// handle [roll, throttle, pitch] gesture
var hand_handler = function (frame) {
    var hands = frame.hands // leap detects all hands in field of vision

    // if drone is not flying, and current action is not takeoff and landing
    if (!isFlying && current_action !== '1,0' && current_action !== '2,0'){
        current_action = updateAction('no_action',0);
        return;
    }
    // if drone is not flying, and current action is landing
    else if(!isFlying && current_action === '2,0'){
        setTimeout(function (){
            isFlying = false;
            isStopped = true;
            current_action = updateAction('no_action',0);
        }, timeout*2);
        return;
    }
    // if no hand detected and drone is moving
    else if (hands.length === 0 && !isStopped) {
        isStopped = true;
        setTimeout(function (){
            if(isFlying){
                current_action = updateAction('no_action',0);
                //console.log("hover!");
            }
        }, timeout);
        return;
    }
    // if only one [right or left] hand is present, go into statement
    else if (hands.length === 1){
        var iBox = frame.interactionBox; // get the virtual space
        var hand = hands[0]; // Only one hand
        //var hand_rotation_speed = hand.palmVelocity;
        //console.log(Math.abs(hand_rotation_speed[1]));
        
        var pos = hand.palmPosition;  // tracks palm of first hand
        var normalizedPoint = iBox.normalizePoint(pos, false); // Normalize them [0 to 1]
            
        var xPos = normalizedPoint[0] + 0.5; // position of hand on x axis
        var yPos = normalizedPoint[1] + 0.5; // position of hand on y axis
        var zPos = normalizedPoint[2] + 0.5; // position of hand on z axis

        //console.log("x:"+xPos+" ,z:"+zPos+" ,y:"+yPos);
        
        //Adjust all [x,y,z] to range [-1,1]
        var adjX = 0;
        var adjY = 0;
        var adjZ = 0;
        if((xPos >= 0 && xPos <= 2) && (yPos >= 0 && yPos <= 3) && (zPos >= 0 && zPos <= 2)){
            //f(x) = (((x-min)*(b-a))/(max-min))+a;
            adjX = ((xPos - 0)*(2/2)) - 1; 
            adjY = (((yPos - 0) * 2 ) / (3)) - 1; 
            adjZ = ((zPos - 0)*(2/2)) - 1; 
        }
        else{
            return;
        }

        //console.log("x:"+adjX+" ,z:"+adjZ+" ,y:"+adjY);

        // calculate the palm position on x,y,z axis and set the threshold for each action
        if ((Math.abs(adjX) > 0.70 || Math.abs(adjY) > 0.80 || Math.abs(adjZ) > 0.70) && isFlying){ // max value of {ROLL,THROTTLE,PITCH} on [x,y,z]-axis
            return;
        }
        if (Math.abs(adjX) < 0.4 && Math.abs(adjY) < 0.5 && Math.abs(adjZ) < 0.4 && isFlying && !isStopped){ // hover if less than min value
            isStopped = true;
            setTimeout(function (){
                if(isFlying && !isYawing){
                    current_action = updateAction('no_action',0);
                    //console.log("hover!");
                }
            }, timeout);
            return;
        }
        if (Math.abs(adjX) >= 0.4 && isFlying) { // within some range of x-axis, set action for {ROLL}
            isStopped = false;
            setTimeout(function (){
                current_action = updateAction('roll',adjX.toPrecision(4)); // [roll in leap] is [pitch in drone]
                //console.log("roll");
            }, timeout/2);
        }
        if (Math.abs(adjY) >= 0.5 && isFlying) { // within some range of y-axis, set action for {THROTTLE}
            isStopped = false;
            setTimeout(function (){
                current_action = updateAction('throttle',adjY.toPrecision(4)); // [throttle in leap] is [throttle in drone]
                //console.log("throttle");
            }, timeout/3);
        }
        if (Math.abs(adjZ) >= 0.4 && isFlying) { // within some range of z-axis, set action for {PITCH}
            isStopped = false;
            setTimeout(function (){
                current_action = updateAction('pitch',(adjZ * -1).toPrecision(4)); // [pitch in leap] is [roll in drone]
                //console.log("pitch");
            }, timeout/4);
        }
    }
}

// function to get extended finger on array - [0,1,1,0,0], where 1 is for extended finger
function getExtendedFingers(frame){
	var extendedFingers = new Array(); // define new array
	if(frame.valid){ // check if the given frame is valid
		if(frame.fingers.length > 0)
		{
			for(var j=0; j < 5; j++)
			{
				var finger = frame.fingers[j];
				// Put 1 to array if an extended finger present
				if(finger.extended) extendedFingers[j] = 1;
				else{ extendedFingers[j] = 0; }
			}
		}
	}
	return extendedFingers;
}

// update current_action [action,speed]
function updateAction(action, action_speed){
    var str_return_val = "0,0"; // no_action,speed=0
    if (actionMap.indexOf(action) === -1 || actionMap.indexOf(action) === 0){
        return str_return_val;
    }
    str_return_val = actionMap.indexOf(action) + ',' + action_speed;
    return str_return_val;
}

module.exports = LeapDroneBLECharacteristic;
