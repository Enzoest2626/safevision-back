package com.safevision.back.infrastructure.storage;


import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.safevision.back.infrastructure.config.S3Properties;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;

import java.net.URI;
import java.net.URL;

class EvidencePresignServiceTest {

    @Test
    void presignGetUrl_retornaUrlDelPresigner() throws Exception {
        S3Presigner presigner = mock(S3Presigner.class);
        PresignedGetObjectRequest presigned = mock(PresignedGetObjectRequest.class);
        URL fakeUrl = URI.create("https://safevision-evidencia.s3.amazonaws.com/incidents/2026-08-10/abc/photo.jpg?X-Amz-Signature=xyz").toURL();
        when(presigned.url()).thenReturn(fakeUrl);
        when(presigner.presignGetObject(any(GetObjectPresignRequest.class))).thenReturn(presigned);

        EvidencePresignService service = new EvidencePresignService(
                new S3Properties("safevision-evidencia", "us-east-1", 15), presigner);

        String url = service.presignGetUrl("incidents/2026-08-10/abc/photo.jpg");

        assertThat(url).isEqualTo(fakeUrl.toString());
    }

    @Test
    void presignGetUrl_usaBucketYKeyCorrectos() {
        S3Presigner presigner = mock(S3Presigner.class);
        PresignedGetObjectRequest presigned = mock(PresignedGetObjectRequest.class);
        when(presigned.url()).thenAnswer(inv -> URI.create("https://example.com/x").toURL());
        when(presigner.presignGetObject(any(GetObjectPresignRequest.class))).thenReturn(presigned);

        EvidencePresignService service = new EvidencePresignService(
                new S3Properties("mi-bucket", "us-east-1", 15), presigner);

        service.presignGetUrl("incidents/2026-08-10/abc/clip.mp4");

        var captor = org.mockito.ArgumentCaptor.forClass(GetObjectPresignRequest.class);
        verify(presigner).presignGetObject(captor.capture());
        assertThat(captor.getValue().getObjectRequest().bucket()).isEqualTo("mi-bucket");
        assertThat(captor.getValue().getObjectRequest().key()).isEqualTo("incidents/2026-08-10/abc/clip.mp4");
    }
}
