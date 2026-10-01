package com.safevision.back.application.ports.out;

import com.safevision.back.domain.model.Camera;

/**
 * Puerto driven: notifica (best-effort, vía HTTP) la configuración vigente de
 * una cámara (rtsp_url + active) para que el módulo CV sepa a qué conectarse
 * y si debe seguir intentando, sin tener que consultarlo por REST.
 */
public interface CameraConfigPublisherPort {

    void publishCameraConfig(Camera camera);
}
