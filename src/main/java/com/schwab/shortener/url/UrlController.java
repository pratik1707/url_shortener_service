package com.schwab.shortener.url;

import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

@RestController
public class UrlController {

    private final UrlService urlService;

    public UrlController(UrlService urlService) {
        this.urlService = urlService;
    }

    @PostMapping("/urls")
    public ResponseEntity<CreateUrlResponse> create(@Valid @RequestBody CreateUrlRequest request) {
        CreateUrlResponse response = urlService.create(request);
        return ResponseEntity.created(URI.create(response.shortUrl())).body(response);
    }

    @GetMapping("/urls/{code}/stats")
    public ResponseEntity<UrlStatsResponse> stats(@PathVariable("code") String code) {
        return ResponseEntity.ok(urlService.stats(code));
    }

    /**
     * 302 rather than 301 on purpose. A permanent redirect is cached by the browser,
     * which would take the server out of the loop for every later click - no way to
     * enforce expiry, no way to retarget a link, and no click data if analytics are
     * added. The cost is that every redirect is a request we must serve.
     *
     * <p>The pattern only accepts letters and digits, which is all a generated code or an
     * alias can contain. Without it this mapping would also swallow paths like
     * {@code /index.html} and the console would never load.
     */
    @GetMapping("/{code:[0-9A-Za-z]+}")
    public ResponseEntity<Void> redirect(@PathVariable("code") String code) {
        String target = urlService.resolve(code);
        HttpHeaders headers = new HttpHeaders();
        headers.setLocation(URI.create(target));
        headers.setCacheControl("no-store");
        return new ResponseEntity<>(headers, HttpStatus.FOUND);
    }
}
