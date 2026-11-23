// SPDX-License-Identifier: AGPL-3.0-only

/// Samsung'un geliştirici sertifikalarını veren ara sertifikalar (Tizen Studio'dan, değiştirilmeden; Android'de
/// assets/samsung-ca). Swift paketine kaynak eklemeden gelsin diye metin olarak gömülüdür.
enum SamsungCaData {
    static let pems: [(name: String, pem: String)] = [
        ("vd_tizen_dev_author_ca", """
        -----BEGIN CERTIFICATE-----
        MIIEfzCCA2egAwIBAgIEaFN6QzANBgkqhkiG9w0BAQ0FADBeMRowGAYDVQQKDBFU
        aXplbiBBc3NvY2lhdGlvbjEaMBgGA1UECwwRVGl6ZW4gQXNzb2NpYXRpb24xJDAi
        BgNVBAMMG1RpemVuIERldmVsb3BlcnMgUm9vdCBDbGFzczAeFw0yNTA2MTkwNDI2
        MzhaFw00MDA2MTkwNDI2MzhaMIGSMQswCQYDVQQGEwJLUjEaMBgGA1UECAwRUmVw
        dWJsaWMgb2YgS29yZWExEzARBgNVBAcMClN1d29uIENpdHkxJjAkBgNVBAoMHVNh
        bXN1bmcgRWxlY3Ryb25pY3MgQ28uLCBMdGQuMQswCQYDVQQLDAJWRDEdMBsGA1UE
        AwwUU2Ftc3VuZyBWRCBBdXRob3IgQ0EwggIiMA0GCSqGSIb3DQEBAQUAA4ICDwAw
        ggIKAoICAQCNLJUDeRdCsRgFNc0VW3Jwx1V/H9EcNmuA0RwLqyJyurv0pRyClBSh
        iPBBnJP+rt91k9zhqpTp5r/8xUnIWyyuCd7PassOp4po3ZiLv/0yFclSjIpYIOHY
        yIHZkamCm6+L5fSmOzXnzwRId/1rb2LKuz9AP3Hk9I0TSYxlSFbz0jkbAxvcGgrZ
        A6siohXUwKRxWfhZTpPQsWd9ZtNRQmg6XYouK9h06TxJ8mYHIpvaDwfnxFPtYdAj
        ljCAjUr/UdNPKnC13JCWTCDQXK5ZPSbLxQZD5tDbZSw7kjXFGIHts8bQDN2knMqa
        l1dzZjpa5pvrRiWeimg59duMMKzs8l96Hap+13ND6+sN+0gvFc4JuzBUoqRaQqW/
        WHl7d1+ySC0z2KUhc4bRUK28rT8fWJ0YkDpxjTvxuM6qdaDnraPnJO6Cze1MoD7y
        CDcLkWUmMOv8yxD1cpZcWN7ZMlUzKt73TqHce9zxkvo5QB3VsXPC8H5LP32vRB03
        XBhqL6yDDejioyEtpgn3WOdkHz6sJqTvoKe8fSoEa+S6SXctsVBI5RMToq9q20rN
        jCSVGur56WVc8jkXuc3RSxgFzMx3RVunfy12hQnbFTWPwfPFS+YO/eHC2w6O9nB9
        sm7lHsm+PPc5Nrxty7AS/qVvwLPlkgDwPF7U4LT90ycz+wYHC8LIRwIDAQABoxAw
        DjAMBgNVHRMEBTADAQH/MA0GCSqGSIb3DQEBDQUAA4IBAQBNSFmmWl9AjcxAyNWQ
        BlO8/Wrc70MlB2JpZ55Vuw2e8rQvWmBC5SjNRHmIu57HgMMGuj0oudCjH/fUkIIk
        4XC8DMAj82wUMPKHaihDzylEtj4Otti1SNNjdCIvVPJg3FzcqWR/8xxrYrfoAWsS
        2eTTFpx2Ogc3n4XevbwfDPQiWZhE88cbuzWcHIBbwRVU4kjEdFXcsuj6hLijMXkd
        /BFgtUOhpo+5H3OnLKdl9yjovOP9A4H3/Ip1ptM7QskVNjLdgJ8wSzZvFSVrYL7O
        ft82Jy86eKrQ9kDg46qaDbBfUzRs8akbSfImsskYO97EB+GXbtx92EBhBSHFKe/G
        AM0p
        -----END CERTIFICATE-----
        """),
        ("author_ca", """
        -----BEGIN CERTIFICATE-----
        MIIDdTCCAl2gAwIBAgICaFYwDQYJKoZIhvcNAQELBQAwXjEaMBgGA1UECgwRVGl6
        ZW4gQXNzb2NpYXRpb24xGjAYBgNVBAsMEVRpemVuIEFzc29jaWF0aW9uMSQwIgYD
        VQQDDBtUaXplbiBEZXZlbG9wZXJzIFJvb3QgQ2xhc3MwHhcNMTUwMjEwMDEwNDAx
        WhcNMzAwMjA2MDEwNDAxWjCBijELMAkGA1UEBhMCS1IxFDASBgNVBAgTC1NvdXRo
        IEtvcmVhMQ4wDAYDVQQHEwVTdXdvbjEmMCQGA1UEChMdU2Ftc3VuZyBFbGVjdHJv
        bmljcyBDby4sIEx0ZC4xCzAJBgNVBAsTAlZEMSAwHgYDVQQDExdTYW1zdW5nIEF1
        dGhvciBDQSBDbGFzczCCASIwDQYJKoZIhvcNAQEBBQADggEPADCCAQoCggEBAK/i
        LAudLDIHXfqj5iLi/Izdo4rXMUdbkXYu9az9TgGfUqOmJi203kFFG1T5TQJfVMaB
        vry+MKLYPzIUWsTcrjelDYVTWmEMucxm1lJvX0drZfXdi/EK7TzWEr0sR7ZR4cQu
        Mgv80edK+H0ppFturPumrh/u8yVqTIHQQhfYbOIjW4939BdO5IG03F+8SrN5Udoe
        /YCtKPS3najZRxx3WqITVvV3AEKd2CualD/9y4Y+y+V7m90habypqWX2rnoMk5AG
        86wFyADkzEv0zoIve//atS2cHRGJx7Com/XXU8OBSsDavwf+6h5AVRzr9ETcPTYo
        Rrqt0g5h++xyckvGJKcCAwEAAaMQMA4wDAYDVR0TBAUwAwEB/zANBgkqhkiG9w0B
        AQsFAAOCAQEAM4cZsU/keJf7D7blzloKbt2a+iuVs6AVR0P/OjB49vmFyozm8oZR
        cWmxzFSb4+yz5GOjCZtpynKgJWfQzL8JZ8zJW8OfMznRFn+sGDMvX3XsDrKkwsrt
        V0rs5E8WMqYmvH1TmFI9SvP3wgV/nKObF2mgyzZC+czZsfUSyNSJxOduNreLA111
        I7Zyibq+ulwrEs9EltuetBDih2g6XXH60ggXAr7myuCgfn8IW6zSZB3oA0Vc/CIO
        P9UEfm0LhrW28RgtDMWOXz8QygtMoKZ6R9TrG9S7cJP0rWeZisyQ7LE4Aasrk7HN
        Yn1Be+g2m4cUTvs6fLLwyQpARYZJwRHBMg==
        -----END CERTIFICATE-----
        """),
        ("vd_tizen_dev_public2", """
        -----BEGIN CERTIFICATE-----
        MIIEvDCCA6SgAwIBAgIQEFcJZvLWjnZy2s9RsDWhmTANBgkqhkiG9w0BAQ0FADCB
        kTELMAkGA1UEBhMCS1IxFDASBgNVBAgMC1NvdXRoIEtvcmVhMQ4wDAYDVQQHDAVT
        dXdvbjEmMCQGA1UECgwdU2Ftc3VuZyBFbGVjdHJvbmljcyBDby4sIEx0ZC4xCzAJ
        BgNVBAsMAlZEMScwJQYDVQQDDB5WRCBERVZFTE9QRVIgUHVibGljIFJvb3QgQ2xh
        c3MwHhcNMjUwNjE3MTAyMDEyWhcNMjkwNjE2MjEwMDAwWjCBjzELMAkGA1UEBhMC
        S1IxFDASBgNVBAgMC1NvdXRoIEtvcmVhMQ4wDAYDVQQHDAVTdXdvbjEmMCQGA1UE
        CgwdU2Ftc3VuZyBFbGVjdHJvbmljcyBDby4sIEx0ZC4xCzAJBgNVBAsMAlZEMSUw
        IwYDVQQDDBxWRCBERVZFTE9QRVIgUHVibGljIENBIENsYXNzMIICIjANBgkqhkiG
        9w0BAQEFAAOCAg8AMIICCgKCAgEAymA8xOpycAu11WBrw4n2+mIdXfA9jzgEI3wY
        dn7o2o4H5o22jihR8aEpsrhW+GTgi+90GxUGwO+o/Nwp5IFp2alNyunF251uVmsj
        1WtHBdWy31ex811H6dR0EfJeK5Ksb0XoOLbQP0+K6UM6ojXpYk1Q0aWBoIYDImgu
        4zsB75Q30KGVlYqvvUtr0KVNao9LhPLWda3hqe2qwFZSwd1d9N+hHO6xPrtnjO2c
        x63kPeWJ3GLmO/0phb+UdMQcGJ2KBpApb/WhN+uDjucvAOwbMFOU3NymS7BWZzS6
        /Rca8WEJkY0XBGndUaniYiqOUisQ5aP8I5HIP9EoM/wtk/ldy1CVckX1DyI1o/ZU
        QWPXzPsb0/cmKOB1N33CFvejvsrJDipIMUW8TgB5qw/WHbiOlyxPDc+G8LjJ2ww1
        wFw9dGVIYpAslcXGyk7r0gHW939mJhUIwP9xZYwGdPKSgjHgjAZrWWrE4D1KqEay
        HVLRhLdhYisHPuqmtuEdbXg32yxCeONdSEz0Z7I2CYn9RKIwBzkuWGjwtCyrvocp
        0AKANve64h0AV1ttlt4hwLbbnLrFtlF1RMFv6mcnz8pg6sLHLcKoqVJ50X9ZvMCC
        j22rlO0FUZ36M1CiV9HJIxH/WoPjftHHuwF+CuJv2kNZuwjTB8dtYpg5PK2sEimB
        4oJomS8CAwEAAaMQMA4wDAYDVR0TBAUwAwEB/zANBgkqhkiG9w0BAQ0FAAOCAQEA
        DtwJOvOAbqqgiLzR9cD6HQ2Fofjcoyj69XjduhL5syzx82ASu2GeZDYOApG9Uwmb
        1w1hXl/UHUFHp0Ob/6SV2wXJTZbBMQlOaYEYwB1crToTfSMTJ5t6LuR+mRQvdBHL
        FM94fRQjQz3ZZOtMsq2WvcWN7ZEUDK1KMh+RP5e7z1GlHCJ00lGqNMrqVf0AMRm3
        iYfT+46/+CL3IY3iWbn2g3e8mPr0U4ZBpAdWawfHRX21oTCU3b5rJ/s1G7dcvkqk
        vu74HrY4f0dpiRCctgdcVuQnWOPFIlLJEkYOiQV1c5Ro/cWUMCo0oR4icvHXb4dP
        Fy+35NUwLfcmza3lFdLgrQ==
        -----END CERTIFICATE-----
        """),
        ("public2", """
        -----BEGIN CERTIFICATE-----
        MIIDrTCCApWgAwIBAgIBATANBgkqhkiG9w0BAQsFADCBkTELMAkGA1UEBhMCS1Ix
        FDASBgNVBAgMC1NvdXRoIEtvcmVhMQ4wDAYDVQQHDAVTdXdvbjEmMCQGA1UECgwd
        U2Ftc3VuZyBFbGVjdHJvbmljcyBDby4sIEx0ZC4xCzAJBgNVBAsMAlZEMScwJQYD
        VQQDDB5WRCBERVZFTE9QRVIgUHVibGljIFJvb3QgQ2xhc3MwHhcNMTQxMjE5MDky
        OTQyWhcNMjkxMjE1MDkyOTQyWjCBjzELMAkGA1UEBhMCS1IxFDASBgNVBAgMC1Nv
        dXRoIEtvcmVhMQ4wDAYDVQQHDAVTdXdvbjEmMCQGA1UECgwdU2Ftc3VuZyBFbGVj
        dHJvbmljcyBDby4sIEx0ZC4xCzAJBgNVBAsMAlZEMSUwIwYDVQQDDBxWRCBERVZF
        TE9QRVIgUHVibGljIENBIENsYXNzMIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIB
        CgKCAQEAr9u+prHP0MtGDqQmDtIpFuFKD1mCmYPEA4OZ6zRH3f3t9cUmXtmAGEDP
        inCHpw9UdGU3JkNnNUd6dFf2HkFBIm8+LepRxR/rEmkHsBHFaLbTDVNZnwBXVs82
        eyMl9wonLp74otysnLAtEA968r7MH5WwwkF73W2vtOqA+PpGOH31LAeLFNPajAVC
        gqiaIKn8eFQuLta2dNrXcZXia+GTXz0PZ0oEs6k1cphhkbe+uV5NL/qWDjF5X1rI
        Gxz/58AwxfF73wP7QPY9Z34vwai0tcUvGAkNXW60FjTj6iZ2N/UxPsuhNa1sSv2s
        rxhWSBAklC3L4A9C6o8sP91WkkdXIQIDAQABoxAwDjAMBgNVHRMEBTADAQH/MA0G
        CSqGSIb3DQEBCwUAA4IBAQCnzmtVT7Zr8BjemLmZu/mLmGizAl/QaufSyCLaPVnU
        lgdReeMdjBvtvNlM9/EJecxiWhAakiZFA664lisAjQjL3bREP9HjOSSDhvx8GNLi
        gjRKOJVivFxz0E4C7K/d9wBlstNCsTe2dRW0mhV+7l1kvbqByfi3kzUrJxaTOev/
        DxaPs9qofe/+Fvor5AcQXiKJ7wuva5HvYZq1z3f3XuEy/tIhv2fkto09aZTNHNje
        HeqiAUQFNrtv8NbHQZZKKaZQbnrT2h4HjtfuzAHaFt1EaO7l2TRE+OEJIpSkKT08
        1kT1zqSmnj38acW/+ExcY7fABrT3oigLsXPBsYxjSAtn
        -----END CERTIFICATE-----
        """),
    ]
}
