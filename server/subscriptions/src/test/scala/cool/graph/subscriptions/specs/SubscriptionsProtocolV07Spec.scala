package cool.graph.subscriptions.specs

import cool.graph.messagebus.pubsub.Only
import cool.graph.shared.project_dsl.SchemaDsl
import org.scalatest._
import org.scalatest.concurrent.ScalaFutures
import play.api.libs.json.{JsNull, Json}
import spray.json.{JsArray, JsNumber, JsObject, JsString}

import scala.concurrent.duration._

class SubscriptionsProtocolV07Spec extends FlatSpec with Matchers with SpecBase with ScalaFutures {
  val schema: SchemaDsl.SchemaBuilder = SchemaDsl.schema()
  val todo: SchemaDsl.ModelBuilder = schema
    .model("Todo")
    .field("text", _.String)
    .field("json", _.Json)
    .field("int", _.Int)
    .field("float", _.Float)

  val project = schema.buildProject()
  val model   = project.getModelByName_!("Todo")

  override def beforeEach() = {
    super.beforeEach()
    testDatabase.setup(project)
    val json = JsArray(JsNumber(1), JsNumber(2), JsObject("a" -> JsString("b")))
    TestData.createTodo("test-node-id", "some todo", json, None, project, model, testDatabase)
    TestData.createTodo("important-test-node-id", "important!", json, None, project, model, testDatabase)
  }

  "sending weird messages" should "result in a parsing error" in {
    testWebsocket(project) { wsClient =>
      wsClient.sendMessage("{}")
      wsClient.expectMessage(cantBeParsedError)

      wsClient.sendMessage("")
      wsClient.expectMessage(cantBeParsedError)
    }
  }

  "sending invalid start messages" should "result in an error" in {
    testInitializedWebsocket(project) { wsClient =>
      val id                = "ioPRfgqN6XMefVW6"
      val noKnownModelError = "The provided query doesn't include any known model name. Please check for the latest subscriptions API."

      // special case: also numbers have to work as subscription id
      wsClient.sendMessage(
        startMessage(id = id, query = "subscription { createPokemon { id name } }")
      )

      wsClient.expectMessage(
        errorMessage(id = id, message = noKnownModelError)
      )

      wsClient.sendMessage(
        startMessage(id = id, query = "subscription { createTodo { id text json } }")
      )

      wsClient.expectMessage(
        errorMessage(id = id, message = noKnownModelError)
      )
    }
  }

  "All subscriptions" should "support the basic subscriptions protocol" in {
    testWebsocket(project) { wsClient =>
      wsClient.sendMessage(connectionInit)
      wsClient.expectMessage(connectionAck)

      val id = "ioPRfgqN6XMefVW6"

      wsClient.sendMessage(startMessage(id = id, query = "subscription { Todo { node { id text json } } }"))
      sleep()

      sssEventsTestKit.publish(
        Only(s"subscription:event:${project.id}:createTodo"),
        s"""{"nodeId":"test-node-id","modelId":"${model.id}","mutationType":"CreateNode"}"""
      )

      wsClient.expectMessage(
        dataMessage(
          id = id,
          payload = """{"Todo":{"node":{"id":"test-node-id","text":"some todo","json":[1,2,{"a":"b"}]}}}"""
        )
      )

      wsClient.sendMessage(stopMessage(id))
    }
  }

  "All subscriptions" should "support the basic subscriptions protocol with number id, null variables and operationName" in {
    testWebsocket(project) { wsClient =>
      wsClient.sendMessage(connectionInit)
      wsClient.expectMessage(connectionAck)

      val id = 3

      wsClient.sendMessage(startMessage(id = id, query = "subscription { Todo { node { id text json } } }", variables = JsNull, operationName = None))
      sleep()

      sssEventsTestKit.publish(
        Only(s"subscription:event:${project.id}:createTodo"),
        s"""{"nodeId":"test-node-id","modelId":"${model.id}","mutationType":"CreateNode"}"""
      )

      wsClient.expectMessage(
        dataMessage(
          id = id,
          payload = """{"Todo":{"node":{"id":"test-node-id","text":"some todo","json":[1,2,{"a":"b"}]}}}"""
        )
      )

      wsClient.sendMessage(stopMessage(id))
    }
  }

  "Using the CREATED mutation filter" should "work" in {
    testInitializedWebsocket(project) { wsClient =>
      wsClient.sendMessage(
        startMessage(id = "2",
                     query = "subscription x { Todo(where: {mutation_in: [CREATED]}) { node { id } } }  mutation y { createTodo { id } }",
                     operationName = "x"))
      wsClient.expectNoMessage(200.milliseconds)
      sleep()

      sssEventsTestKit.publish(
        Only(s"subscription:event:${project.id}:createTodo"),
        s"""{"nodeId":"test-node-id","modelId":"${model.id}","mutationType":"CreateNode"}"""
      )

      wsClient.expectMessage(
        dataMessage(
          id = "2",
          payload = """{"Todo":{"node":{"id":"test-node-id"}}}"""
        )
      )
    }
  }

  "Using the DELETED mutation filter" should "work" in {
    testInitializedWebsocket(project) { wsClient =>
      wsClient.sendMessage(
        startMessage(
          id = "3",
          operationName = "x",
          query = "subscription x { Todo(where: {mutation_in: [DELETED]}) { node { id } } }  mutation y { createTodo { id } }"
        ))

      wsClient.expectNoMessage(200.milliseconds)
      sleep()

      sssEventsTestKit.publish(
        Only(s"subscription:event:${project.id}:deleteTodo"),
        s"""{"nodeId":"test-node-id","node":{"id":"test-node-id","text":"some text"},"modelId":"${model.id}","mutationType":"DeleteNode"}"""
      )

      wsClient.expectMessage(
        dataMessage(
          id = "3",
          payload = """{"Todo":{"node":null}}"""
        )
      )
    }
  }

  "Using the URPDATED mutation filter" should "work" in {
    testInitializedWebsocket(project) { wsClient =>
      wsClient.sendMessage(
        startMessage(
          id = "4",
          query = "subscription { Todo(where: {mutation_in: [UPDATED]}) { node { id text } } } "
        ))

      sleep()

      sssEventsTestKit.publish(
        Only(s"subscription:event:${project.id}:updateTodo"),
        s"""{"nodeId":"test-node-id","modelId":"${model.id}","mutationType":"UpdateNode","changedFields":["text"], "previousValues": "{\\"id\\": \\"text-node-id\\", \\"text\\": \\"asd\\", \\"json\\": [], \\"float\\": 1.23, \\"int\\": 1}"}"""
      )

      wsClient.expectMessage(
        dataMessage(
          id = "4",
          payload = """{"Todo":{"node":{"id":"test-node-id","text":"some todo"}}}"""
        )
      )
    }
  }

  "Create Subscription" should "support the node filters" in {
    testInitializedWebsocket(project) { wsClient =>
      wsClient.sendMessage(
        startMessage(
          id = "3",
          query =
            "subscription asd($text: String!) { Todo(where: {mutation_in: [CREATED] node: {text_contains: $text}}) { mutation node { id } previousValues { id text } updatedFields } }",
          variables = Json.obj("text" -> "some")
        )
      )

      sleep()

      sssEventsTestKit.publish(
        Only(s"subscription:event:${project.id}:createTodo"),
        s"""{"nodeId":"test-node-id","modelId":"${model.id}","mutationType":"CreateNode"}"""
      )

      wsClient.expectMessage(
        dataMessage(
          id = "3",
          payload = """{"Todo":{"mutation":"CREATED","node":{"id":"test-node-id"},"previousValues":null,"updatedFields":null}}"""
        )
      )

      wsClient.sendMessage(stopMessage(id = "3"))
      wsClient.expectNoMessage(3.seconds)
    }
  }

  "Update Subscription" should "support the node filters" in {
    testInitializedWebsocket(project) { wsClient =>
      wsClient.sendMessage(
        startMessage(
          id = "3",
          query =
            "subscription asd($text: String!) { Todo(where: {mutation_in: UPDATED AND: [{updatedFields_contains: \"text\"},{node: {text_contains: $text}}]}) { mutation previousValues { id json int } node { ...todo } } } fragment todo on Todo { id }",
          variables = Json.obj("text" -> "some")
        )
      )

      sleep()

      sssEventsTestKit.publish(
        Only(s"subscription:event:${project.id}:updateTodo"),
        s"""{"nodeId":"test-node-id","modelId":"${model.id}","mutationType":"UpdateNode","changedFields":["text"], "previousValues": "{\\"id\\": \\"text-node-id\\", \\"text\\": \\"asd\\", \\"json\\": null, \\"int\\": 8, \\"createdAt\\": \\"2017\\"}"}"""
      )

      wsClient.expectMessage(
        dataMessage(
          id = "3",
          payload = """{"Todo":{"mutation":"UPDATED","previousValues":{"id":"test-node-id","json":null,"int":8},"node":{"id":"test-node-id"}}}"""
        )
      )
    }
  }

  "Delete Subscription" should "ignore the node filters" in {
    testInitializedWebsocket(project) { wsClient =>
      wsClient.sendMessage(
        startMessage(id = "3",
                     query = "subscription { Todo(where: {mutation_in: [DELETED]}) { node { ...todo } previousValues { id } } } fragment todo on Todo { id }")
      )

      sleep()

      sssEventsTestKit.publish(
        Only(s"subscription:event:${project.id}:deleteTodo"),
        s"""{"nodeId":"test-node-id2","node":{"id":"test-node-id2","text":"some text"},"modelId":"${model.id}","mutationType":"DeleteNode"}"""
      )

      wsClient.expectMessage(
        dataMessage(
          id = "3",
          payload = """{"Todo":{"node":null,"previousValues":{"id":"test-node-id2"}}}"""
        )
      )
    }
  }

  "Subscription" should "regenerate changed schema and work on reconnect" ignore {
    testInitializedWebsocket(project) { wsClient =>
      wsClient.sendMessage(
        startMessage(id = "create-filters", query = "subscription { Todo(where:{node:{text_contains: \"important!\"}}) { node { id text } } }")
      )

      sleep(3000)

      invalidationTestKit.publish(Only(project.id), "")
      wsClient.expectMessage("""{"id":"create-filters","payload":{"message":"Schema changed"},"type":"error"}""")
      sleep()
      // KEEP WORKING ON RECONNECT

      wsClient.sendMessage(
        startMessage(id = "update-filters", query = "subscription { Todo(where:{node:{text_contains: \"important!\"}}) { node { id text } } }")
      )

      sleep(3000)

      sssEventsTestKit.publish(
        Only(s"subscription:event:${project.id}:updateTodo"),
        s"""{"nodeId":"important-test-node-id","modelId":"${model.id}","mutationType":"UpdateNode","changedFields":["text"], "previousValues": "{\\"id\\": \\"text-node-id\\", \\"text\\": \\"asd\\", \\"json\\": null, \\"createdAt\\": \\"2017\\"}"}"""
      )

      wsClient.expectMessage(
        dataMessage(
          id = "update-filters",
          payload = """{"Todo":{"node":{"id":"important-test-node-id","text":"important!"}}}"""
        )
      )

      wsClient.sendMessage(stopMessage("update-filters"))
    }
  }
}
