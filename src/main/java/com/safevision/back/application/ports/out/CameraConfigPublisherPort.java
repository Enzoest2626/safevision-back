package com.safevision.back.application.ports.out;

/**
 * Puerto driven: publica (retained, vía MQTT) la configuración vigente de
 * una cámara (rtsp_url + active) para que el módulo CV sepa a qué conectarse
 * y si debe seguir intentando, sin tener que consultarlo por REST.
 *
 * Direcciona por código (siteCode/zoneCode/cameraCode), no por ID numérico
 * — son los identificadores estables que el CV usa para armar su topic
 * (ver CLAUDE.md).
 */
public interface CameraConfigPublisherPort {

    void publishCameraConfig(String siteCode, String zoneCode, String cameraCode, String rtspUrl, boolean active);
}
