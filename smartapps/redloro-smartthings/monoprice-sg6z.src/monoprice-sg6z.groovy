/**
 *  SmartThings SmartApp: Monoprice 6-Zone Amplifier
 *
 *  Author: tcjennings@hotmail.com
 *
 *  Based on Russound RNET by Author: redloro@gmail.com
 *
 *  Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 *  in compliance with the License. You may obtain a copy of the License at:
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software distributed under the License is distributed
 *  on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License
 *  for the specific language governing permissions and limitations under the License.
 */
import groovy.json.JsonSlurper

definition(
  name: "Monoprice SG6Z",
  namespace: "redloro-smartthings",
  author: "tcjennings@hotmail.com",
  description: "Monoprice 6-Zone Amplifier SmartApp",
  category: "My Apps",
  iconUrl: "https://raw.githubusercontent.com/redloro/smartthings/master/images/monoprice.png",
  iconX2Url: "https://raw.githubusercontent.com/redloro/smartthings/master/images/monoprice.png",
  iconX3Url: "https://raw.githubusercontent.com/redloro/smartthings/master/images/monoprice.png",
  singleInstance: true
)

preferences {
  section("SmartThings Hub") {
    input "hostHub", "hub", title: "Select Hub", multiple: false, required: true
  }
  section("SmartThings Node Proxy") {
    input "proxyAddress", "text", title: "Proxy Address", description: "(ie. 192.168.1.10)", required: true
    input "proxyPort", "text", title: "Proxy Port", description: "(ie. 8080)", required: true, defaultValue: "8080"
    input "authCode", "password", title: "Auth Code", description: "", required: true, defaultValue: "topsecret"
  }
  section("Monoprice Controller") {
    input "enableDiscovery", "bool", title: "Discover Zones (WARNING: all existing zones will be removed)", required: false, defaultValue: false
  }
}

def installed() {
  subscribeToEvents()
}

def subscribeToEvents() {
  subscribe(location, null, lanResponseHandler, [filterEvents:false])
}

def uninstalled() {
  removeChildDevices()
}

def updated() {
  if (settings.enableDiscovery) {
    //remove child devices as we will reload
    removeChildDevices()
  }

  //subscribe to callback/notifications from STNP
  sendCommand('/subscribe/'+getNotifyAddress())

  if (settings.enableDiscovery) {
    //delay discovery for 5 seconds
    runIn(5, discoverChildDevices)
    settings.enableDiscovery = false
  }
}

def lanResponseHandler(evt) {
  def map = stringToMap(evt.stringValue)

  //verify that this message is from STNP IP:Port
  //IP and Port are only set on HTTP GET response and we need the MAC
  if (map.ip == convertIPtoHex(settings.proxyAddress) &&
    map.port == convertPortToHex(settings.proxyPort)) {
      if (map.mac) {
        state.proxyMac = map.mac
      }
  }

  //verify that this message is from STNP MAC
  //MAC is set on both HTTP GET response and NOTIFY
  if (map.mac != state.proxyMac) {
    return
  }

  def headers = getHttpHeaders(map.headers);
  def body = getHttpBody(map.body);
  //log.trace "SmartThings Node Proxy: ${evt.stringValue}"
  //log.trace "Headers: ${headers}"
  //log.trace "Body: ${body}"

  //verify that this message is for this plugin
  if (headers.'stnp-plugin' != 'mpr-sg6z') {
    return
  }

  //log.trace "Monoprice 6Z event: ${evt.stringValue}"
  processEvent(body)
}

private sendCommand(path) {
  //log.trace "Monoprice 6Z send command: ${path}"

  if (settings.proxyAddress.length() == 0 ||
    settings.proxyPort.length() == 0) {
    log.error "SmartThings Node Proxy configuration not set!"
    return
  }

  def host = getProxyAddress()
  def headers = [:]
  headers.put("HOST", host)
  headers.put("Content-Type", "application/json")
  headers.put("stnp-auth", settings.authCode)

  def hubAction = new physicalgraph.device.HubAction(
      method: "GET",
      path: path,
      headers: headers
  )
  sendHubCommand(hubAction)
}

private processEvent(evt) {
  if (evt.type == "discover") {
    //for each controller
    evt.controllers.each {
      addChildDevices(it.controller, it.zones)
    }
  }
  if (evt.type == "zone") {
    updateZoneDevices(evt)
  }
}

private addChildDevices(controller, zones) {
  zones.each {
    def deviceId = getDeviceId(controller, it.zone)
    if (!getChildDevice(deviceId)) {
      addChildDevice("redloro-smartthings", "MPR6Z Zone", deviceId, hostHub.id, ["name": it.name, label: "MPR: "+it.name, completedSetup: true])
      //log.debug "Added zone device: ${deviceId}"
    }
  }

  childDevices*.refresh()
}

private removeChildDevices() {
  getAllChildDevices().each { deleteChildDevice(it.deviceNetworkId) }
}

def discoverChildDevices() {
  sendCommand('/plugins/mpr-sg6z/discover')
}

private updateZoneDevices(evt) {
  //log.debug "updateZoneDevices: ${evt}"

  //setState for all zones in case All On/Off was called
  if (evt.zone == -1) {
    //log.debug "Update all zones"
    childDevices*.zone(evt)
    return
  }

  def zonedevice = getChildDevice(getDeviceId(evt.controller, evt.zone))
  if (zonedevice) {
    zonedevice.zone(evt)
  }
}

private partyMode(evt) {
  childDevices*.partyMode(evt)
}

private getDeviceId(controller, zone) {
  return "mpr-sg6z|${controller}|${zone}"
}

private getHttpHeaders(headers) {
	def obj = [:]
	new String(headers.decodeBase64()).split("\r\n").each {param ->
    def nameAndValue = param.split(":")
    obj[nameAndValue[0]] = (nameAndValue.length == 1) ? "" : nameAndValue[1].trim()
	}
  return obj
}

private getHttpBody(body) {
  def obj = null;
  if (body) {
    def slurper = new JsonSlurper()
    obj = slurper.parseText(new String(body.decodeBase64()))
  }
  return obj
}

private getProxyAddress() {
	return settings.proxyAddress + ":" + settings.proxyPort
}

private getNotifyAddress() {
  return settings.hostHub.localIP + ":" + settings.hostHub.localSrvPortTCP
}

private String convertIPtoHex(ipAddress) {
  return ipAddress.tokenize( '.' ).collect {  String.format( '%02x', it.toInteger() ) }.join().toUpperCase()
}

private String convertPortToHex(port) {
  return port.toString().format( '%04x', port.toInteger() ).toUpperCase()
}
