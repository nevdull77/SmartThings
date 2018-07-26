/**
 *  MyModlet
 *
 *  Copyright 2018 Patrik Karlsson
 *
 *  Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 *  in compliance with the License. You may obtain a copy of the License at:
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software distributed under the License is distributed
 *  on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License
 *  for the specific language governing permissions and limitations under the License.
 *
 */
import groovy.transform.Field

@Field final String   BASE_URI = "https://web.mymodlet.com"
 
metadata {
    definition (name: "MyModlet", namespace: "nevdull77", author: "Patrik Karlsson") {
        capability "Actuator"
        capability "Temperature Measurement"
        capability "Thermostat"
        capability "Configuration"
        capability "Refresh"
        capability "Sensor"
    }

    preferences {
        input name: "username", type: "text", title: "Username", description: "MyModlet username (email address)", required: true, displayDuringSetup: true
        input name: "password", type: "password", title: "Password", description: "MyModlet password", required: true, displayDuringStartup: true
        input name: "modletName", type: "text", title: "Modlet name", description: "The name of the modlet to control", required: true
    }

    simulator {
        // TODO: define status and reply messages here
    }

    tiles {
        valueTile("temperature", "device.temperature", width: 2, height: 2) {
            state("temperature", label:'${currentValue}°', unit:"F",
                backgroundColors:[
                    [value: 31, color: "#153591"],
                    [value: 44, color: "#1e9cbb"],
                    [value: 59, color: "#90d2a7"],
                    [value: 74, color: "#44b621"],
                    [value: 84, color: "#f1d801"],
                    [value: 95, color: "#d04e00"],
                    [value: 96, color: "#bc2323"]
                ]
            )
        }
        standardTile("mode", "device.thermostatMode", inactiveLabel: false, decoration: "flat") {
            state "off", label:'${name}', action:"thermostat.setThermostatMode"
            state "cool", label:'${name}', action:"thermostat.setThermostatMode"
        }
        controlTile("coolSliderControl", "device.coolingSetpoint", "slider", height: 1, width: 2, inactiveLabel: false) {
            state "setCoolingSetpoint", action:"thermostat.setCoolingSetpoint", backgroundColor: "#00a0dc"
        }
        valueTile("coolingSetpoint", "device.coolingSetpoint", inactiveLabel: false, decoration: "flat") {
            state "cool", label:'${currentValue}° cool', unit:"F", backgroundColor:"#ffffff"
        }
        standardTile("refresh", "device.temperature", inactiveLabel: false, decoration: "flat") {
            state "default", action:"refresh.refresh", icon:"st.secondary.refresh"
        }
        main "temperature"
        details(["temperature", "mode", "fanMode", "heatSliderControl", "heatingSetpoint", "coolSliderControl", "coolingSetpoint", "refresh"])
    }
}

// parse events into attributes
def parse(String description) {

}

// Parse broken escaped Json data returned from mymodlet
def parseBrokenJson(brokenJson) {
    def data = brokenJson.replace("\\", "")
    data = data.subSequence(1,data.length()-1) 
    def slurper = new groovy.json.JsonSlurper()
    return slurper.parseText(data)
}

def parseDeviceData(String data) {
    log.debug "parseDeviceData - BEGIN"
    def result = parseBrokenJson(data)

    result.SmartACs.each {
        if ( it?.modlet?.modletName == modletName ) {
            def temperature = it?.thermostat?.currentTemperature
            def targettemp = it?.thermostat?.targetTemperature
            def deviceId = it?.modlet?.modletChannels[0].deviceId
            def modletId = it?.modlet?.modletId
            def mode = it?.modlet?.isOn ? "cool" : "off"

            sendEvent(name: "temperature", value: temperature)
            sendEvent(name: "coolingSetpoint", value: targettemp)
            sendEvent(name: "thermostatMode", value: mode)
            
            state.deviceId = deviceId
            log.debug "name: ${modletName}; temperature: ${temperature}; target: ${targettemp}; deviceId: ${deviceId}; modletId: ${modletId}; mode: ${mode}"
        }
    }
    

}

def updateDeviceData()
{
    def res = myModletRequest("GET", "/Devices/UpdateData")
    if ( res.success ) {
        parseDeviceData(res.raw)
    }    
}

//
// TODO: If login fails write to state variable to stop further polling until credentials have been fixed
//
def login()
{
    log.debug "Login BEGIN"
    def body = "{\"data\": \"{\\\"Email\\\": \\\"${username}\\\", \\\"Password\\\": \\\"${password}\\\"}\"}"
    state.session = ""
    def result = myModletRequest("POST", "/Account/Login", body, false)
    log.debug "Login result: ${result.json.data}"
    log.debug "Login END"
}

def myModletRequest(String method, String path, String body="", retry=true) {
    def params = [
        path: path,
        uri: BASE_URI,
        requestContentType: "application/json",
        contentType: "plain/text",
        headers: [
            "Cookie": state.session,
        ],
        body: body,
    ]
    log.debug "body: ${body}"
    try {
        def data
        if (method == "GET") {
            httpGet(params) { resp ->
                def contentType = resp.getLastHeader("Content-Type").getValue()
                if (!contentType.contains("application/json") && retry ) {
                    login()
                    return myModletRequest(method, path, body, false)
                }
                data = resp.data.getText()
            }
        }
        else {
            httpPost(params) { resp ->
                def contentType = resp.getLastHeader("Content-Type").getValue()
                if (!contentType.contains("application/json") && !retry) {
                    login()
                    return myModletRequest(method, path, body, false)
                }

                data = resp.data.getText()

                if ( path == "/Account/Login" ) {
                    log.debug "processing cookies"
                    resp.getHeaders("Set-Cookie").each {
                        if (state.session != "") {
                            state.session = state.session + "; "
                        }
                        state.session = state.session + "${it.value.split(';')[0]}"
                    }
                }
            }
        }
        def result = parseBrokenJson(data)
        log.debug "path: ${path}; result: ${result.data}"

        return [
            success: true,
            raw: data,
            json: result
        ]
    } catch (e) {
        log.debug "myModletRequest - something went wrong: $e"
    }
    return [
        success: false
    ]
}

def sendSwitch(String mode) {
    def path = "/Devices/SwitchOn"
    if (mode == "off") {
        path = "/Devices/SwitchOff"
    }
    log.debug "sendSwitch: ${mode}"
    def body = "{\"data\": \"{\\\"id\\\": \\\"${state.deviceId}\\\"}\"}"
    def res = myModletRequest("POST", path, body)
    if ( res.success ) {
    	def thermostatMode = (mode == "off") ? "off" : "cool"
    	log.debug "thermostatMode: ${thermostatMode}"
		sendEvent(name: "thermostatMode", value: thermostatMode)    
    }
    return res
}

def logout() {
    log.debug "logout() BEGIN"
    def params = [
        path: "/Account/Logout",
        uri: BASE_URI,
        headers: [
            "Cookie": state.session,
            "Referer": "https://web.mymodlet.com/"
        ],
    ]

    try {
        httpGet(params) { resp ->
            state.session = ""
            log.debug "logged out"
        }
    } catch (e) {
        log.debug "logout - something went wrong: $e"
    }
    log.debug "logout() END"
}

def off() {
    log.debug "Executing 'off'"
    sendSwitch("off")
}

def heat() {
    log.debug "heat is not supported by this thermostat"
}

def emergencyHeat() {
    log.debug "emergencyHeat is not supported by this thermostat"
}

def cool() {
    log.debug "Executing 'cool'"
    sendSwitch("on")
}

def installed() {
    updated()
}

def updated() {
    log.debug "Executing 'updated'"
    unschedule()
    runEvery10Minutes(refresh)
    runIn(2, refresh)
}

def refresh() {
    log.debug "refresh() - BEGIN"
    updateDeviceData()
    log.debug "refresh() - END"
}

def modes() {
    ["off", "cool"]
}

def auto() {
    log.debug "auto is not supported by this thermostat"
}

def setThermostatMode() {
    log.debug "Executing 'setThermostatMode'"
    def currentMode = device.currentState("thermostatMode")?.value
    def mode = (currentMode == "cool") ? "off" : "cool"
    return setThermostatMode(mode)
}

def setThermostatMode(String mode) {
    def res = "$mode"()
}


def reqNewCoolingSetpoint(data) {
    def degreesInteger = data.degrees
    def mode = device.currentState("thermostatMode")?.value
	def isThermostated = mode == "cool" ? true : false
    def body = "{\"data\":\"{\\\"DeviceId\\\": \\\"${state.deviceId}\\\", \\\"TargetTemperature\\\": \\\"${degreesInteger}\\\", \\\"IsThermostated\\\":${isThermostated}}\"}"
    def result = myModletRequest("POST", "/Devices/UserSettingsUpdate", body)
    log.debug "setCoolingSetpoint result: ${result.json.data}"   
}


def setCoolingSetpoint(degrees) {
    log.debug "Executing 'setCoolingSetpoint'"
    if (degrees != null) {
        def temperatureScale = getTemperatureScale()
        def degreesInteger = degrees.toInteger()
        log.debug "setCoolingSetpoint({$degreesInteger} ${temperatureScale})"
        sendEvent("name": "coolingSetpoint", "value": degreesInteger, "unit": temperatureScale)
        unschedule(reqNewCoolingSetpoint)
        // delay change 2 seconds, to give time for another quick adjustment if the slider is used
        // the API seems to struggle with registering the change if it's to close to the last
        runIn(2, reqNewCoolingSetpoint, [data: [degrees: degreesInteger]])
    }
}
