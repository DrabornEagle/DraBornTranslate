// SPDX-License-Identifier: AGPL-3.0-only

using System.Net;

namespace GameTranslator.Core.Tests;

public class PoolActivityTests
{
    private static Func<HttpResponseMessage> Reply(string body, HttpStatusCode status = HttpStatusCode.OK) =>
        () => new HttpResponseMessage(status) { Content = new StringContent(body) };

    [Fact]
    public async Task AsksTheActiveAddressNextToTheLinesAddressAndSendsNothing()
    {
        var server = new FakePoolServer();
        server.Replies.Enqueue(Reply("""{"now":79,"today":329}"""));

        var activity = await PoolActivity.FetchAsync(LinePool.OwnEndpoint, server);

        Assert.Equal(new PoolActivity(79, 329), activity);
        var request = Assert.Single(server.Received);
        Assert.Equal(HttpMethod.Get, request.Method);
        Assert.Equal("https://gametranslator-pool.gametranslator-pool.workers.dev/v1/active", request.Url.ToString());
        Assert.Equal("", request.Body);
    }

    [Theory]
    [InlineData("""{"error":"server_error"}""", HttpStatusCode.InternalServerError)]
    [InlineData("not json", HttpStatusCode.OK)]
    [InlineData("""{"now":"many"}""", HttpStatusCode.OK)]
    [InlineData("""{"now":-1,"today":3}""", HttpStatusCode.OK)]
    public async Task BrokenAnswerShowsNothing(string body, HttpStatusCode status)
    {
        var server = new FakePoolServer();
        server.Replies.Enqueue(Reply(body, status));
        Assert.Null(await PoolActivity.FetchAsync(LinePool.OwnEndpoint, server));
    }

    [Fact]
    public async Task UnreachableServerShowsNothing()
    {
        var server = new FakePoolServer { Fallback = null };
        Assert.Null(await PoolActivity.FetchAsync(LinePool.OwnEndpoint, server));
    }

    [Fact]
    public async Task TodayIsNeverBelowNow()
    {
        var server = new FakePoolServer();
        server.Replies.Enqueue(Reply("""{"now":5,"today":4}"""));
        Assert.Equal(new PoolActivity(5, 5), await PoolActivity.FetchAsync(LinePool.OwnEndpoint, server));
    }
}
