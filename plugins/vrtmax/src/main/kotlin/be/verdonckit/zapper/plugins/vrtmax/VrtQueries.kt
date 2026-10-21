package be.verdonckit.zapper.plugins.vrtmax

/**
 * Ingekorte GraphQL-queries, enkel met velden die ook de Kodi-add-on gebruikt.
 * Breekt het schema, dan geeft de API een "errors"-array terug met het
 * ongeldige veld: dat komt letterlijk in het health-rapport voor de fix-agent.
 */
internal object VrtQueries {

    private val TILE_NODE = """
        __typename
        ... on ProgramTile {
          id
          title
          description
          link
          image { templateUrl }
          preview {
            heading {
              ... on Banner {
                image { templateUrl }
                titleArt { templateUrl }
              }
            }
          }
        }
        ... on EpisodeTile {
          id
          title
          description
          available
          image { templateUrl }
          action { ... on LinkAction { link } }
          primaryMeta { type value shortValue longValue }
          progress { durationInSeconds }
        }
    """

    private val CONNECTION = """
        edges { cursor node { $TILE_NODE } }
        pageInfo { endCursor hasNextPage }
    """

    val TILE_LIST = """
        query TileList(${'$'}listId: ID!, ${'$'}lazyItemCount: Int = 50, ${'$'}after: ID) {
          list(listId: ${'$'}listId) {
            __typename
            ... on PaginatedTileList {
              paginatedItems(first: ${'$'}lazyItemCount, after: ${'$'}after) { $CONNECTION }
            }
            ... on StaticTileList {
              paginatedItems(first: ${'$'}lazyItemCount, after: ${'$'}after) { $CONNECTION }
            }
          }
        }
    """

    private val LIST_FIELDS = """
        ... on PaginatedTileList { listId title tileContentType }
        ... on StaticTileList { listId title tileContentType }
        ... on LazyTileList { listId title }
    """

    val PROGRAM_PAGE = """
        query ProgramPage(${'$'}pageId: ID!) {
          page(id: ${'$'}pageId) {
            __typename
            ... on ProgramPage {
              header {
                title
                titleArt { templateUrl }
                richDescription { html }
              }
              menu {
                items {
                  title
                  components {
                    __typename
                    $LIST_FIELDS
                    ... on ContainerNavigation {
                      items {
                        title
                        components {
                          __typename
                          $LIST_FIELDS
                        }
                      }
                    }
                  }
                }
              }
            }
          }
        }
    """

    val STREAM_ID = """
        query StreamId(${'$'}pageId: ID!) {
          page(id: ${'$'}pageId) {
            __typename
            ... on EpisodePage {
              player { modes { __typename ... on VideoPlayerMode { streamId } } }
            }
            ... on LivestreamPage {
              player { modes { __typename ... on VideoPlayerMode { streamId } } }
            }
          }
        }
    """

    /** Homepage-componenten: hieruit halen we de lijst "Populair" (of de eerste programmalijst). */
    val HOME_LISTS = """
        query Page(${'$'}pageId: ID!, ${'$'}componentCount: Int = 50) {
          page(id: ${'$'}pageId) {
            ... on IPage {
              paginatedComponents(first: ${'$'}componentCount) {
                edges {
                  node {
                    __typename
                    ... on PaginatedTileList { listId title tileContentType banner { title } }
                    ... on StaticTileList { listId title tileContentType banner { title } }
                  }
                }
              }
            }
          }
        }
    """
}
